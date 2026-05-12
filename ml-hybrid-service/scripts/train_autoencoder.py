"""
Train an Anomaly Autoencoder model.
Unsupervised anomaly detection based on reconstruction error.
Uses shared_ml components for preprocessing, splitting, and thresholding.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import time
import re
from pathlib import Path
from datetime import datetime

import numpy as np
import pandas as pd
import joblib
from sklearn.metrics import (
    accuracy_score,
    precision_score,
    recall_score,
    f1_score,
    roc_auc_score,
    confusion_matrix,
    precision_recall_curve,
    average_precision_score,
)
from sklearn.pipeline import Pipeline

sys.path.insert(0, "/app")

from app.models.unsupervised.anomaly_models import AnomalyAutoencoder
from shared_ml.evaluation.data_split import temporal_split
from shared_ml.evaluation.thresholding import (
    find_threshold_by_fbeta,
    find_threshold_by_cost,
)
from shared_ml.preprocessing.encoders import SafeOneHotEncoder
from shared_ml.preprocessing.scalers import SafeScaler
from shared_ml.preprocessing.encoders.feature_selectors import TreeFeatureSelector
from shared_ml.preprocessing.behavioral import TemporalFeatureTransformer
from shared_ml.utils.postal_codes import (
    process_customer_postal_fit,
    process_customer_postal_transform,
    process_merchant_postal_fit,
    process_merchant_postal_transform,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
    stream=sys.stderr,
)
logger = logging.getLogger(__name__)


# ------------------------------------------------------------------------------
# Helper for ID columns
# ------------------------------------------------------------------------------
def extract_numeric_from_id(value):
    if pd.isna(value):
        return 0

    try:
        if isinstance(value, (int, float)):
            return float(value)

        s = str(value)

        if "-" in s:
            parts = s.split("-")
            if len(parts) > 1 and parts[1].replace(".", "", 1).isdigit():
                return float(parts[1])

        numbers = re.findall(r"\d+", s)

        if numbers:
            return float(numbers[0])

        return 0

    except Exception:
        return 0


def process_id_columns(df):
    df_processed = df.copy()
    id_columns = ["Account_Id", "Device_Id"]

    for col in id_columns:
        if col in df_processed.columns:
            df_processed[col] = df_processed[col].apply(extract_numeric_from_id).astype(np.float32)

    return df_processed


# ------------------------------------------------------------------------------
# JSON serialization helper
# ------------------------------------------------------------------------------
def make_json_safe(obj):
    if isinstance(obj, (np.integer, np.int64, np.int32)):
        return int(obj)

    if isinstance(obj, (np.floating, np.float64, np.float32)):
        return float(obj)

    if isinstance(obj, np.ndarray):
        return obj.tolist()

    if isinstance(obj, dict):
        return {k: make_json_safe(v) for k, v in obj.items()}

    if isinstance(obj, (list, tuple)):
        return [make_json_safe(x) for x in obj]

    return obj


# ------------------------------------------------------------------------------
# Hyperparameter loader
# ------------------------------------------------------------------------------
def load_hyperparameters(hyperparam_arg: str) -> dict:
    try:
        return json.loads(hyperparam_arg)

    except json.JSONDecodeError:
        try:
            with open(hyperparam_arg, "r") as f:
                return json.load(f)

        except Exception as e:
            raise ValueError(f"Could not load hyperparameters from '{hyperparam_arg}': {e}")


# ------------------------------------------------------------------------------
# Metrics helper
# ------------------------------------------------------------------------------
def compute_binary_metrics_at_threshold(y_true, y_score, threshold: float) -> dict:
    y_pred = (y_score >= threshold).astype(int)
    cm = confusion_matrix(y_true, y_pred, labels=[0, 1])

    try:
        auc_roc = roc_auc_score(y_true, y_score)
    except ValueError:
        auc_roc = None

    try:
        pr_auc = average_precision_score(y_true, y_score)
    except ValueError:
        pr_auc = None

    return {
        "threshold": float(threshold),
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "precision": float(precision_score(y_true, y_pred, zero_division=0)),
        "recall": float(recall_score(y_true, y_pred, zero_division=0)),
        "f1_score": float(f1_score(y_true, y_pred, zero_division=0)),
        "auc_roc": None if auc_roc is None else float(auc_roc),
        "pr_auc": None if pr_auc is None else float(pr_auc),
        "avg_anomaly_score": float(np.mean(y_score)),
        "predicted_positive_rate": float(np.mean(y_pred)),
        "confusion_matrix": {
            "true_negatives": int(cm[0, 0]),
            "false_positives": int(cm[0, 1]),
            "false_negatives": int(cm[1, 0]),
            "true_positives": int(cm[1, 1]),
        },
    }


# ------------------------------------------------------------------------------
# Identify categorical columns dynamically
# ------------------------------------------------------------------------------
def identify_categorical_columns(df: pd.DataFrame) -> list[str]:
    expected_cats = [
        "category",
        "Channel",
        "client_usual_region",
        "client_usual_channel",
        "client_usual_category",
    ]

    exclude = {
        "TX_Time",
        "Account_Id",
        "Device_Id",
        "Customer_Region",
        "Merchant_Region",
    }

    categorical_cols = []

    for col in df.columns:
        if col in exclude:
            continue

        if col in expected_cats or df[col].dtype == "object" or df[col].dtype.name == "category":
            categorical_cols.append(col)

    return categorical_cols


# ------------------------------------------------------------------------------
# Preprocessing pipeline
# ------------------------------------------------------------------------------
def create_preprocessing_pipeline(categorical_columns: list[str], has_temporal: bool) -> Pipeline:
    steps = []

    if has_temporal:
        steps.append(("temporal", TemporalFeatureTransformer()))

    steps.append(
        (
            "onehot",
            SafeOneHotEncoder(
                categorical_cols=categorical_columns,
                include_raw_cats=True,
            ),
        )
    )

    steps.append(("selector", TreeFeatureSelector()))
    steps.append(("scaler", SafeScaler(scaler_type="robust")))

    return Pipeline(steps)


# ------------------------------------------------------------------------------
# Main
# ------------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="Train Autoencoder for bank fraud detection")
    parser.add_argument("--dataset-path", required=True)
    parser.add_argument("--dataset-id", required=True)
    parser.add_argument("--job-id", required=True)
    parser.add_argument("--timestamp", required=True)
    parser.add_argument("--hyperparameters", required=True)
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--random-seed", type=int, default=42)

    args = parser.parse_args()

    started_at = datetime.now()
    overall_start = time.time()

    logger.info("Starting Autoencoder training for dataset %s", args.dataset_id)

    original_stdout = sys.stdout
    sys.stdout = sys.stderr

    try:
        hp = load_hyperparameters(args.hyperparameters)

        model_name = hp.get("model_name", "AUTOENCODER")
        selected_original_features = hp.get("selected_original_features", [])

        if not selected_original_features:
            raise ValueError("No features selected for training")

        dataset_path = Path(args.dataset_path)

        if not dataset_path.exists():
            raise FileNotFoundError(f"Dataset not found: {dataset_path}")

        # Load data
        if dataset_path.suffix.lower() == ".parquet":
            df = pd.read_parquet(dataset_path)
        else:
            df = pd.read_csv(dataset_path)

        if not df.columns.is_unique:
            logger.warning("Duplicate column names detected. Keeping first occurrence.")
            df = df.loc[:, ~df.columns.duplicated(keep="first")]

        if "fraud" not in df.columns:
            raise ValueError("Target column 'fraud' not found in dataset")

        if "TX_Time" in df.columns:
            df["TX_Time"] = pd.to_datetime(df["TX_Time"], errors="coerce")
            before = len(df)
            df = df.dropna(subset=["TX_Time"]).reset_index(drop=True)

            if len(df) < before:
                logger.warning("Dropped %d rows with invalid TX_Time", before - len(df))

        # Use only selected features
        available_features = [f for f in selected_original_features if f in df.columns]
        missing = set(selected_original_features) - set(available_features)

        if missing:
            logger.warning("Features not in dataset: %s", missing)

        if not available_features:
            raise ValueError("None of the selected features are available in the dataset")

        # Always include TX_Time for temporal split
        feature_cols_for_split = available_features.copy()

        if "TX_Time" not in feature_cols_for_split and "TX_Time" in df.columns:
            feature_cols_for_split.append("TX_Time")

        X = df[feature_cols_for_split].copy()
        y = df["fraud"].values

        df_processed = X.copy()
        df_processed["fraud"] = y

        has_temporal = "TX_Time" in selected_original_features

        if "TX_Time" not in df_processed.columns:
            raise ValueError("TX_Time column missing")

        # Temporal split
        df_train, df_val, df_test = temporal_split(
            df_processed,
            time_col="TX_Time",
            train_ratio=0.7,
            val_ratio=0.15,
        )

        logger.info("Applying per-split preprocessing...")

        # ID columns
        df_train = process_id_columns(df_train)
        df_val = process_id_columns(df_val)
        df_test = process_id_columns(df_test)

        # Postal codes
        if "Customer_Postal_Code" in df_train.columns:
            customer_region_categories = process_customer_postal_fit(df_train)
            df_train = process_customer_postal_transform(df_train, customer_region_categories)
            df_val = process_customer_postal_transform(df_val, customer_region_categories)
            df_test = process_customer_postal_transform(df_test, customer_region_categories)

        if "Merchant_Postal_Code" in df_train.columns:
            merchant_region_categories = process_merchant_postal_fit(df_train)
            df_train = process_merchant_postal_transform(df_train, merchant_region_categories)
            df_val = process_merchant_postal_transform(df_val, merchant_region_categories)
            df_test = process_merchant_postal_transform(df_test, merchant_region_categories)

        # Separate features and target
        X_train = df_train.drop("fraud", axis=1)
        X_val = df_val.drop("fraud", axis=1)
        X_test = df_test.drop("fraud", axis=1)

        y_train = df_train["fraud"].values
        y_val = df_val["fraud"].values
        y_test = df_test["fraud"].values

        # Ensure numeric
        for X_set in [X_train, X_val, X_test]:
            for col in X_set.columns:
                if X_set[col].dtype == "object":
                    X_set[col] = pd.to_numeric(X_set[col], errors="coerce").fillna(0)

        logger.info("Train: %d rows (fraud rate %.4f)", len(X_train), y_train.mean())
        logger.info("Val:   %d rows (fraud rate %.4f)", len(X_val), y_val.mean())
        logger.info("Test:  %d rows (fraud rate %.4f)", len(X_test), y_test.mean())

        categorical_columns = identify_categorical_columns(X_train)
        logger.info("Categorical columns to encode: %s", categorical_columns)

        preprocessor = create_preprocessing_pipeline(categorical_columns, has_temporal)

        X_train_proc = preprocessor.fit_transform(X_train)
        X_val_proc = preprocessor.transform(X_val)
        X_test_proc = preprocessor.transform(X_test)

        if hasattr(X_train_proc, "columns") and "TX_Time" in X_train_proc.columns:
            X_train_proc = X_train_proc.drop("TX_Time", axis=1)
            X_val_proc = X_val_proc.drop("TX_Time", axis=1)
            X_test_proc = X_test_proc.drop("TX_Time", axis=1)

        if hasattr(X_train_proc, "toarray"):
            X_train_proc = X_train_proc.toarray().astype(np.float32)
            X_val_proc = X_val_proc.toarray().astype(np.float32)
            X_test_proc = X_test_proc.toarray().astype(np.float32)

        X_train_proc = np.asarray(X_train_proc, dtype=np.float32)
        X_val_proc = np.asarray(X_val_proc, dtype=np.float32)
        X_test_proc = np.asarray(X_test_proc, dtype=np.float32)

        # ========== AUTOENCODER MODEL ==========
        encoding_dim = hp.get("encoding_dim", 32)
        epochs = hp.get("epochs", 20)
        batch_size = hp.get("batch_size", 1024)

        # Kept for model internals, but final recommendation is controlled by threshold_objective.
        threshold_metric = hp.get("threshold_metric", "fbeta")
        beta = float(hp.get("beta", 2.0))

        model = AnomalyAutoencoder(
            encoding_dim=encoding_dim,
            epochs=epochs,
            batch_size=batch_size,
            threshold_metric=threshold_metric,
            beta=beta,
        )

        # Train only on normal transactions
        normal_mask_train = y_train == 0
        X_train_normal = X_train_proc[normal_mask_train]

        if len(X_train_normal) == 0:
            raise ValueError("No normal transactions available for Autoencoder training")

        logger.info(
            "Training autoencoder on %d normal transactions out of %d total train rows",
            len(X_train_normal),
            len(X_train_proc),
        )

        logger.info("Fitting Autoencoder on normal transactions...")

        train_start = time.time()
        model.fit(X_train_normal, y_train[normal_mask_train], X_val_proc, y_val)
        train_time = time.time() - train_start

        final_model = model

        # ========== THRESHOLD CALCULATIONS ==========
        y_val_proba_raw = final_model.predict_proba(X_val_proc)

        if y_val_proba_raw.ndim == 1:
            y_val_score = y_val_proba_raw
        else:
            y_val_score = y_val_proba_raw[:, 1]

        precision_vals, recall_vals, thresholds_pr = precision_recall_curve(y_val, y_val_score)

        if len(thresholds_pr) == 0:
            pr_threshold = 0.5
            best_f1 = 0.0
        else:
            f1_scores = (
                2 * precision_vals[:-1] * recall_vals[:-1]
                / (precision_vals[:-1] + recall_vals[:-1] + 1e-9)
            )
            best_idx = int(np.argmax(f1_scores))
            pr_threshold = float(thresholds_pr[best_idx])
            best_f1 = float(f1_scores[best_idx])

        logger.info("PR-based threshold (max F1): %.4f (F1=%.4f)", pr_threshold, best_f1)

        fbeta_threshold, fbeta_score_val = find_threshold_by_fbeta(
            y_val,
            y_val_score,
            beta=beta,
        )
        fbeta_threshold = float(fbeta_threshold)
        fbeta_score_val = float(fbeta_score_val)

        logger.info(
            "F-beta threshold (β=%.2f): %.4f (score=%.4f)",
            beta,
            fbeta_threshold,
            fbeta_score_val,
        )

        cost_threshold, min_cost = find_threshold_by_cost(
            y_val,
            y_val_score,
            cost_fp=float(hp.get("threshold_cost_fp", 1.0)),
            cost_fn=float(hp.get("threshold_cost_fn", 10.0)),
        )
        cost_threshold = float(cost_threshold)
        min_cost = float(min_cost)

        logger.info("Cost-based threshold: %.4f (min_cost=%.0f)", cost_threshold, min_cost)

        # ========== RECOMMENDED THRESHOLD POLICY ==========
        threshold_objective = str(hp.get("threshold_objective", "f1")).lower().strip()

        if threshold_objective == "f1":
            recommended_threshold = pr_threshold
            recommended_method = "f1"
            recommended_objective = "maximize F1-score on validation"

        elif threshold_objective == "fbeta":
            recommended_threshold = fbeta_threshold
            recommended_method = "fbeta"
            recommended_objective = f"maximize F-beta on validation (beta={beta:g})"

        elif threshold_objective == "cost":
            recommended_threshold = cost_threshold
            recommended_method = "cost"
            recommended_objective = "minimize business cost on validation"

        else:
            logger.warning(
                "Unknown threshold_objective '%s'. Falling back to Autoencoder default: f1.",
                threshold_objective,
            )
            threshold_objective = "f1"
            recommended_threshold = pr_threshold
            recommended_method = "f1"
            recommended_objective = "maximize F1-score on validation"

        logger.info(
            "Recommended threshold selected by %s: %.4f",
            recommended_method,
            recommended_threshold,
        )

        # ========== FINAL THRESHOLD POLICY ==========
        user_threshold_raw = hp.get("detection_threshold")
        max_prob = float(np.max(y_val_score))

        if user_threshold_raw is not None:
            user_defined_threshold = float(user_threshold_raw)
            final_threshold = user_defined_threshold
            final_threshold_source = "user_defined"

            logger.info(
                "Using user-supplied threshold from UI as final applied threshold: %.4f",
                final_threshold,
            )

            if final_threshold > max_prob:
                logger.warning(
                    "User threshold %.4f > max validation score %.4f -> it may detect nothing on validation.",
                    final_threshold,
                    max_prob,
                )

            if final_threshold < 0.0 or final_threshold > 1.0:
                logger.warning(
                    "User threshold %.4f is outside [0, 1]. Autoencoder score is expected on normalized scale.",
                    final_threshold,
                )

        else:
            user_defined_threshold = None
            final_threshold = recommended_threshold
            final_threshold_source = "recommended"

            logger.info(
                "No UI threshold supplied. Using recommended threshold as final applied threshold: %.4f",
                final_threshold,
            )

        final_model.threshold_ = float(final_threshold)

        # ========== TEST EVALUATION ==========
        y_test_proba_raw = final_model.predict_proba(X_test_proc)

        if y_test_proba_raw.ndim == 1:
            y_test_score = y_test_proba_raw
        else:
            y_test_score = y_test_proba_raw[:, 1]

        test_metrics_at_final = compute_binary_metrics_at_threshold(
            y_test,
            y_test_score,
            final_threshold,
        )

        test_metrics_at_recommended = compute_binary_metrics_at_threshold(
            y_test,
            y_test_score,
            recommended_threshold,
        )

        accuracy = test_metrics_at_final["accuracy"]
        precision = test_metrics_at_final["precision"]
        recall = test_metrics_at_final["recall"]
        f1 = test_metrics_at_final["f1_score"]
        auc_roc = test_metrics_at_final["auc_roc"]
        pr_auc = test_metrics_at_final["pr_auc"]
        cm_dict = test_metrics_at_final["confusion_matrix"]

        logger.info(
            "Test metrics with final threshold %.4f (%s):",
            final_threshold,
            final_threshold_source,
        )
        logger.info("  Accuracy:  %.4f", accuracy)
        logger.info("  Precision: %.4f", precision)
        logger.info("  Recall:    %.4f", recall)
        logger.info("  F1:        %.4f", f1)
        logger.info("  AUC-ROC:   %s", "N/A" if auc_roc is None else f"{auc_roc:.4f}")
        logger.info("  PR-AUC:    %s", "N/A" if pr_auc is None else f"{pr_auc:.4f}")

        logger.info(
            "Test metrics if recommended threshold %.4f was applied: Precision=%.4f, Recall=%.4f, F1=%.4f",
            recommended_threshold,
            test_metrics_at_recommended["precision"],
            test_metrics_at_recommended["recall"],
            test_metrics_at_recommended["f1_score"],
        )

        # ========== SAVE ARTIFACTS ==========
        timestamp = args.timestamp

        output_models_dir = Path("/tmp/fraud-work/output/models")
        output_pipelines_dir = Path("/tmp/fraud-work/output/pipelines")

        output_models_dir.mkdir(parents=True, exist_ok=True)
        output_pipelines_dir.mkdir(parents=True, exist_ok=True)

        pipeline_path = output_pipelines_dir / f"pipeline_{timestamp}.joblib"
        joblib.dump(preprocessor, pipeline_path)
        logger.info("Preprocessor saved to %s", pipeline_path)

        base_filename = f"trained_model_{timestamp}"
        model_path = output_models_dir / f"{base_filename}.joblib"
        temp_model_path = output_models_dir / f"{base_filename}.tmp"

        try:
            model.save(temp_model_path)
            temp_model_path.rename(model_path)
            logger.info("Model saved to %s (%d bytes)", model_path, model_path.stat().st_size)

        except Exception as e:
            logger.error("Failed to save model: %s", e)
            raise

        finally:
            if temp_model_path.exists():
                temp_model_path.unlink()

        # ========== FEATURE IMPORTANCE ==========
        feature_names = X_train.columns.tolist()

        try:
            feature_importance_map = model.get_feature_importance(feature_names=feature_names)
            feature_importance_map = dict(
                sorted(
                    feature_importance_map.items(),
                    key=lambda x: x[1],
                    reverse=True,
                )
            )
        except Exception:
            logger.warning("Could not compute feature importance, skipping.")
            feature_importance_map = {}

        ended_at = datetime.now()
        total_duration = time.time() - overall_start

        result_raw = {
            "status": "COMPLETED",
            "job_id": args.job_id,
            "dataset_id": args.dataset_id,
            "model_name": model_name,
            "model_path": str(model_path),
            "pipeline_path": str(pipeline_path),
            "selected_original_features": available_features,
            "feature_importance": feature_importance_map,
            "hyperparameters_used": {
                "encoding_dim": encoding_dim,
                "epochs": epochs,
                "batch_size": batch_size,
                "threshold_metric": threshold_metric,
                "beta": beta,
            },
            "training_mode": {
                "scoring": "unsupervised_autoencoder_reconstruction_error",
                "threshold_selection": "supervised_on_validation_anomaly_score",
                "uses_labels_for_scoring": False,
                "uses_labels_for_threshold": True,
                "ui_threshold_used_directly": user_defined_threshold is not None,
            },
            "metrics": {
                "accuracy": accuracy,
                "precision": precision,
                "recall": recall,
                "f1_score": f1,
                "auc_roc": auc_roc,
                "pr_auc": pr_auc,
                "avg_anomaly_score": float(y_test_score.mean()),
            },
            "confusion_matrix": cm_dict,
            "thresholds": {
                "pr_optimal": pr_threshold,
                "pr_f1": best_f1,
                "fbeta": fbeta_threshold,
                "fbeta_score": fbeta_score_val,
                "cost": cost_threshold,
                "min_cost": min_cost,

                "recommended": recommended_threshold,
                "recommended_method": recommended_method,
                "recommended_objective": recommended_objective,

                "final": final_threshold,
                "final_source": final_threshold_source,

                "user_defined": user_defined_threshold,

                "recommended_test_f1": test_metrics_at_recommended["f1_score"],
                "recommended_test_precision": test_metrics_at_recommended["precision"],
                "recommended_test_recall": test_metrics_at_recommended["recall"],
            },
            "training_stats": {
                "train_rows": int(len(X_train)),
                "train_normal_rows": int(np.sum(normal_mask_train)),
                "val_rows": int(len(X_val)),
                "test_rows": int(len(X_test)),
                "fraud_rate_test": float(y_test.mean()),
                "train_time_seconds": float(train_time),
                "input_dim": int(X_train_proc.shape[1]),
            },
            "start_time": started_at.isoformat(),
            "end_time": ended_at.isoformat(),
            "duration_seconds": float(total_duration),
        }

        result = make_json_safe(result_raw)

        sys.stdout = original_stdout
        print(json.dumps(result))

    except Exception as e:
        logger.exception("Autoencoder Training failed")

        sys.stdout = original_stdout

        error_result = {
            "status": "FAILED",
            "error": str(e),
            "job_id": getattr(args, "job_id", None),
            "dataset_id": getattr(args, "dataset_id", None),
        }

        print(json.dumps(error_result))
        sys.exit(1)


if __name__ == "__main__":
    main()