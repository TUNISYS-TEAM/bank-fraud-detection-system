"""
Train a Random Forest model for bank fraud detection.
No data leakage – per-split preprocessing, cost-sensitive learning, dynamic thresholding.
Uses shared_ml components for splitting, postal codes, preprocessing.
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
from sklearn.ensemble import RandomForestClassifier
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

# Shared ML imports
from shared_ml.evaluation.data_split import temporal_split
from shared_ml.evaluation.thresholding import (
    find_threshold_by_fbeta,
    find_threshold_by_cost,
)
from shared_ml.preprocessing.behavioral.temporal_features import TemporalFeatureTransformer
from shared_ml.preprocessing.encoders.categorical_encoder import SafeOneHotEncoder
from shared_ml.preprocessing.encoders.feature_selectors import TreeFeatureSelector
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
# Helpers for ID columns
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
    steps = [
        (
            "onehot",
            SafeOneHotEncoder(
                categorical_cols=categorical_columns,
                include_raw_cats=True,
            ),
        )
    ]

    if has_temporal:
        steps.append(("temporal", TemporalFeatureTransformer()))

    steps.append(("selector", TreeFeatureSelector()))

    return Pipeline(steps)


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
        "avg_fraud_score": float(np.mean(y_score)),
        "predicted_positive_rate": float(np.mean(y_pred)),
        "confusion_matrix": {
            "true_negatives": int(cm[0, 0]),
            "false_positives": int(cm[0, 1]),
            "false_negatives": int(cm[1, 0]),
            "true_positives": int(cm[1, 1]),
        },
    }


# ------------------------------------------------------------------------------
# ONNX export
# ------------------------------------------------------------------------------
def export_to_onnx_direct(model, output_path, input_dim):
    try:
        from skl2onnx import convert_sklearn
        from skl2onnx.common.data_types import FloatTensorType
        import onnx

        initial_type = [("float_input", FloatTensorType([None, input_dim]))]
        onnx_model = convert_sklearn(model, initial_types=initial_type)

        onnx.save_model(onnx_model, str(output_path))
        logger.info("ONNX model exported to: %s", output_path)

        return str(output_path)

    except Exception as e:
        logger.error("ONNX export failed: %s", e)
        return None


# ------------------------------------------------------------------------------
# Main
# ------------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="Train Random Forest for bank fraud detection")
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

    logger.info("Starting Random Forest training for dataset %s", args.dataset_id)

    original_stdout = sys.stdout
    sys.stdout = sys.stderr

    try:
        hp = load_hyperparameters(args.hyperparameters)

        model_name = hp.get("model_name", "Random Forest")
        selected_original_features = hp.get("selected_original_features", [])

        if not selected_original_features:
            raise ValueError("No features selected for training")

        dataset_path = Path(args.dataset_path)

        if not dataset_path.exists():
            raise FileNotFoundError(f"Dataset not found: {dataset_path}")

        # Load dataset
        if dataset_path.suffix.lower() == ".parquet":
            df = pd.read_parquet(dataset_path)
        else:
            df = pd.read_csv(dataset_path)

        if not df.columns.is_unique:
            logger.warning("Duplicate column names detected. Keeping first occurrence.")
            df = df.loc[:, ~df.columns.duplicated(keep="first")]

        if "TX_Time" in df.columns:
            df["TX_Time"] = pd.to_datetime(df["TX_Time"], errors="coerce")
            before = len(df)
            df = df.dropna(subset=["TX_Time"]).reset_index(drop=True)

            if len(df) < before:
                logger.warning("Dropped %d rows with invalid TX_Time", before - len(df))

        if "TX_Time" not in df.columns:
            raise ValueError("TX_Time column missing, cannot perform temporal split")

        if "fraud" not in df.columns:
            raise ValueError("fraud column missing, cannot train supervised Random Forest")

        # Keep selected features + TX_Time + fraud
        features_in_dataset = [f for f in selected_original_features if f in df.columns]
        missing = set(selected_original_features) - set(features_in_dataset)

        if missing:
            logger.warning("Features not in dataset: %s", missing)

        split_cols = features_in_dataset + ["TX_Time", "fraud"]
        split_cols = list(dict.fromkeys(split_cols))
        df_split = df[split_cols].copy()

        # Temporal split
        df_train, df_val, df_test = temporal_split(
            df_split,
            time_col="TX_Time",
            train_ratio=0.7,
            val_ratio=0.15,
        )

        has_temporal = "TX_Time" in selected_original_features

        train_feat_cols = [c for c in features_in_dataset if c != "TX_Time" or has_temporal]

        if not has_temporal and "TX_Time" in train_feat_cols:
            train_feat_cols.remove("TX_Time")

        X_train = df_train[train_feat_cols].copy()
        X_val = df_val[train_feat_cols].copy()
        X_test = df_test[train_feat_cols].copy()

        y_train = df_train["fraud"].values
        y_val = df_val["fraud"].values
        y_test = df_test["fraud"].values

        # ID processing
        X_train = process_id_columns(X_train)
        X_val = process_id_columns(X_val)
        X_test = process_id_columns(X_test)

        # Postal code processing
        if "Customer_Postal_Code" in X_train.columns:
            customer_region_categories = process_customer_postal_fit(X_train)
            X_train = process_customer_postal_transform(X_train, customer_region_categories)
            X_val = process_customer_postal_transform(X_val, customer_region_categories)
            X_test = process_customer_postal_transform(X_test, customer_region_categories)

        if "Merchant_Postal_Code" in X_train.columns:
            merchant_region_categories = process_merchant_postal_fit(X_train)
            X_train = process_merchant_postal_transform(X_train, merchant_region_categories)
            X_val = process_merchant_postal_transform(X_val, merchant_region_categories)
            X_test = process_merchant_postal_transform(X_test, merchant_region_categories)

        logger.info("Train: %d rows (fraud rate %.4f)", len(X_train), y_train.mean())
        logger.info("Val:   %d rows (fraud rate %.4f)", len(X_val), y_val.mean())
        logger.info("Test:  %d rows (fraud rate %.4f)", len(X_test), y_test.mean())

        # Preprocessing
        categorical_columns = identify_categorical_columns(X_train)
        logger.info("Categorical columns to encode: %s", categorical_columns)

        preprocessor = create_preprocessing_pipeline(categorical_columns, has_temporal)

        X_train_proc = preprocessor.fit_transform(X_train)
        X_val_proc = preprocessor.transform(X_val)
        X_test_proc = preprocessor.transform(X_test)

        for df_proc in (X_train_proc, X_val_proc, X_test_proc):
            if hasattr(df_proc, "columns") and "TX_Time" in df_proc.columns:
                df_proc.drop("TX_Time", axis=1, inplace=True)

        try:
            feature_names = preprocessor.get_feature_names_out()
        except Exception:
            feature_names = [f"feature_{i}" for i in range(X_train_proc.shape[1])]

        logger.info("Processed feature shape: %s", X_train_proc.shape)

        # ========== RANDOM FOREST ==========
        fraud_weight = hp.get("fraud_weight")

        if fraud_weight is None:
            fraud_ratio = y_train.mean()
            fraud_weight = min(1.0 / fraud_ratio, 50.0) if fraud_ratio > 0 else 30.0

        logger.info("Using fraud class weight: %.1f", fraud_weight)

        class_weight = {0: 1, 1: fraud_weight}

        fixed_params = {
            "min_samples_split": 20,
            "min_samples_leaf": 10,
            "class_weight": class_weight,
            "random_state": args.random_seed,
            "n_jobs": -1,
            "verbose": 0,
            "max_features": "sqrt",
            "bootstrap": True,
            "max_samples": 0.8,
        }

        user_params = {
            "n_estimators": hp.get("n_estimators", 100),
            "max_depth": hp.get("max_depth", 15),
        }

        rf_params = {**fixed_params, **user_params}

        logger.info("Training Random Forest with params: %s", rf_params)

        train_start = time.time()

        rf_model = RandomForestClassifier(**rf_params)
        rf_model.fit(X_train_proc, y_train)

        train_time = time.time() - train_start

        # ========== THRESHOLD CALCULATIONS ==========
        y_val_proba = rf_model.predict_proba(X_val_proc)[:, 1]
        max_prob = float(y_val_proba.max())

        logger.info("Validation max probability: %.4f", max_prob)

        threshold_beta = float(hp.get("threshold_beta", 2.0))
        cost_fp = float(hp.get("threshold_cost_fp", 1.0))
        cost_fn = float(hp.get("threshold_cost_fn", 10.0))

        # Default changed to f1 because the UI mainly compares F1/precision/recall.
        threshold_objective = str(hp.get("threshold_objective", "f1")).lower().strip()

        precision_vals, recall_vals, thresholds_pr = precision_recall_curve(y_val, y_val_proba)

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
            y_val_proba,
            beta=threshold_beta,
        )

        fbeta_threshold = float(fbeta_threshold)
        fbeta_score_val = float(fbeta_score_val)

        logger.info(
            "F-beta (β=%.2f): threshold=%.4f, score=%.4f",
            threshold_beta,
            fbeta_threshold,
            fbeta_score_val,
        )

        cost_threshold, min_cost = find_threshold_by_cost(
            y_val,
            y_val_proba,
            cost_fp=cost_fp,
            cost_fn=cost_fn,
        )

        cost_threshold = float(cost_threshold)
        min_cost = float(min_cost)

        logger.info(
            "Cost-based: threshold=%.4f, min_cost=%.0f (FP cost=%.2f, FN cost=%.2f)",
            cost_threshold,
            min_cost,
            cost_fp,
            cost_fn,
        )

        # ========== RECOMMENDED THRESHOLD POLICY ==========
        if threshold_objective == "f1":
            recommended_threshold = pr_threshold
            recommended_method = "f1"
            recommended_objective = "maximize F1-score on validation"

        elif threshold_objective == "fbeta":
            recommended_threshold = fbeta_threshold
            recommended_method = "fbeta"
            recommended_objective = f"maximize F-beta on validation (beta={threshold_beta:g})"

        elif threshold_objective == "cost":
            recommended_threshold = cost_threshold
            recommended_method = "cost"
            recommended_objective = "minimize business cost on validation"

        else:
            logger.warning(
                "Unknown threshold_objective '%s'. Falling back to Random Forest default: f1.",
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
        # The final applied threshold must be the UI threshold if supplied.
        # Otherwise, it falls back to the recommended threshold.
        user_threshold_raw = hp.get("detection_threshold")

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
                    "User threshold %.4f > max validation probability %.4f -> it may detect nothing on validation.",
                    final_threshold,
                    max_prob,
                )

            if final_threshold < 0.0 or final_threshold > 1.0:
                logger.warning(
                    "User threshold %.4f is outside [0, 1]. Random Forest predict_proba scores are expected in [0, 1].",
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

        # Validation metrics for comparison
        validation_metrics_at_recommended = compute_binary_metrics_at_threshold(
            y_val,
            y_val_proba,
            recommended_threshold,
        )

        validation_metrics_at_final = compute_binary_metrics_at_threshold(
            y_val,
            y_val_proba,
            final_threshold,
        )

        validation_metrics_at_user = None

        if user_defined_threshold is not None:
            validation_metrics_at_user = compute_binary_metrics_at_threshold(
                y_val,
                y_val_proba,
                user_defined_threshold,
            )

        # ========== TEST EVALUATION ==========
        y_test_proba = rf_model.predict_proba(X_test_proc)[:, 1]

        test_metrics_at_final = compute_binary_metrics_at_threshold(
            y_test,
            y_test_proba,
            final_threshold,
        )

        test_metrics_at_recommended = compute_binary_metrics_at_threshold(
            y_test,
            y_test_proba,
            recommended_threshold,
        )

        test_metrics_at_user = None

        if user_defined_threshold is not None:
            test_metrics_at_user = compute_binary_metrics_at_threshold(
                y_test,
                y_test_proba,
                user_defined_threshold,
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
        logger.info(
            "  Accuracy: %.4f, Precision: %.4f, Recall: %.4f, F1: %.4f, AUC-ROC: %s, PR-AUC: %s",
            accuracy,
            precision,
            recall,
            f1,
            "N/A" if auc_roc is None else f"{auc_roc:.4f}",
            "N/A" if pr_auc is None else f"{pr_auc:.4f}",
        )

        logger.info(
            "Test metrics if recommended threshold %.4f was applied: Accuracy=%.4f, Precision=%.4f, Recall=%.4f, F1=%.4f",
            recommended_threshold,
            test_metrics_at_recommended["accuracy"],
            test_metrics_at_recommended["precision"],
            test_metrics_at_recommended["recall"],
            test_metrics_at_recommended["f1_score"],
        )

        # Feature importance
        importances = rf_model.feature_importances_
        feature_importance_map = dict(
            sorted(
                zip(feature_names, importances),
                key=lambda x: x[1],
                reverse=True,
            )
        )

        # ========== SAVE ARTIFACTS ==========
        timestamp = args.timestamp

        output_models_dir = Path("/tmp/fraud-work/output/models")
        output_onnx_dir = Path("/tmp/fraud-work/output/onnx")
        output_pipelines_dir = Path("/tmp/fraud-work/output/pipelines")

        for d in [output_models_dir, output_onnx_dir, output_pipelines_dir]:
            d.mkdir(parents=True, exist_ok=True)

        base_filename = f"trained_model_{timestamp}"

        # Save preprocessor
        pipeline_path = output_pipelines_dir / f"pipeline_{timestamp}.joblib"
        joblib.dump(preprocessor, pipeline_path)
        logger.info("Preprocessor saved to %s", pipeline_path)

        # Save classifier
        model_path = output_models_dir / f"{base_filename}.joblib"
        temp_model_path = output_models_dir / f"{base_filename}.tmp"

        joblib.dump(rf_model, temp_model_path)
        temp_model_path.rename(model_path)

        logger.info("Classifier saved to %s (%d bytes)", model_path, model_path.stat().st_size)

        # ONNX export
        onnx_file = output_onnx_dir / f"{base_filename}.onnx"
        onnx_path = export_to_onnx_direct(rf_model, onnx_file, X_train_proc.shape[1])

        if not onnx_path:
            raise RuntimeError("ONNX export failed")

        ended_at = datetime.now()
        total_duration = time.time() - overall_start

        result_raw = {
            "status": "COMPLETED",
            "job_id": args.job_id,
            "dataset_id": args.dataset_id,
            "model_name": model_name,
            "onnx_path": str(onnx_path),
            "model_path": str(model_path),
            "pipeline_path": str(pipeline_path),
            "selected_original_features": selected_original_features,
            "feature_importance": feature_importance_map,
            "hyperparameters_used": rf_params,
            "metrics": {
                # Official metrics: computed using the final applied threshold.
                "accuracy": accuracy,
                "precision": precision,
                "recall": recall,
                "f1_score": f1,
                "auc_roc": auc_roc,
                "pr_auc": pr_auc,
                "avg_fraud_score": float(y_test_proba.mean()),
                "computed_on": "test",
                "threshold_used": final_threshold,
                "threshold_source": final_threshold_source,
            },
            "confusion_matrix": cm_dict,
            "thresholds": {
                # Legacy flat fields expected by existing backend/frontend
                "pr_optimal": pr_threshold,
                "pr_f1": best_f1,
                "fbeta": fbeta_threshold,
                "fbeta_score": fbeta_score_val,
                "cost": cost_threshold,
                "min_cost": min_cost,

                # Recommended threshold: independent from the UI threshold
                "recommended": recommended_threshold,
                "recommended_method": recommended_method,
                "recommended_objective": recommended_objective,
                "recommended_computed_on": "validation",

                # Final applied threshold: UI threshold if provided, else recommended threshold
                "final": final_threshold,
                "final_source": final_threshold_source,
                "metrics_computed_on": "test",

                # UI threshold metadata
                "user_defined": user_defined_threshold,
                "user_defined_used_for_metrics": user_defined_threshold is not None,

                # Comparison metrics
                "validation_metrics_at_recommended_threshold": validation_metrics_at_recommended,
                "validation_metrics_at_final_threshold": validation_metrics_at_final,
                "validation_metrics_at_user_threshold": validation_metrics_at_user,
                "test_metrics_at_recommended_threshold": test_metrics_at_recommended,
                "test_metrics_at_final_threshold": test_metrics_at_final,
                "test_metrics_at_user_threshold": test_metrics_at_user,
            },
            "training_stats": {
                "train_rows": int(len(X_train)),
                "val_rows": int(len(X_val)),
                "test_rows": int(len(X_test)),
                "fraud_rate_test": float(y_test.mean()),
                "train_time_seconds": float(train_time),
            },
            "start_time": started_at.isoformat(),
            "end_time": ended_at.isoformat(),
            "duration_seconds": float(total_duration),
        }

        result = make_json_safe(result_raw)

        sys.stdout = original_stdout
        print(json.dumps(result))

    except Exception as e:
        logger.exception("Random Forest training failed")

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