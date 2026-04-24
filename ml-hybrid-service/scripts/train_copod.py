"""
Train a COPOD (Copula-Based Outlier Detection) model.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import time
import warnings
from pathlib import Path
from datetime import datetime

import numpy as np
import pandas as pd
import joblib
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score, f1_score,
    roc_auc_score, confusion_matrix, precision_recall_curve, average_precision_score
)
from sklearn.pipeline import Pipeline

sys.path.insert(0, '/app')

from app.models.unsupervised.anomaly_models import COPODFraudModel
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
    format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
    stream=sys.stderr
)
logger = logging.getLogger(__name__)

warnings.filterwarnings(
    "ignore",
    message="Precision loss occurred in moment calculation due to catastrophic cancellation.*",
    category=RuntimeWarning,
)

EXCLUDED_FEATURE_NAMES = {
    "Account_Id",
    "Device_Id",
    "transaction_id",
    "client_id",
    "device_id",
    "account_id",
}


def make_json_safe(obj):
    if isinstance(obj, (np.integer, np.int64, np.int32)):
        return int(obj)
    elif isinstance(obj, (np.floating, np.float64, np.float32)):
        return float(obj)
    elif isinstance(obj, np.ndarray):
        return obj.tolist()
    elif isinstance(obj, dict):
        return {k: make_json_safe(v) for k, v in obj.items()}
    elif isinstance(obj, (list, tuple)):
        return [make_json_safe(x) for x in obj]
    else:
        return obj


def load_hyperparameters(hyperparam_arg: str) -> dict:
    try:
        return json.loads(hyperparam_arg)
    except json.JSONDecodeError:
        try:
            with open(hyperparam_arg, 'r') as f:
                return json.load(f)
        except Exception as e:
            raise ValueError(f"Could not load hyperparameters from '{hyperparam_arg}': {e}")


def precision_at_k(y_true: np.ndarray, scores: np.ndarray, k: int) -> float:
    y_true = np.asarray(y_true)
    scores = np.asarray(scores)
    n = len(y_true)
    if n == 0:
        return 0.0
    k = max(1, min(int(k), n))
    top_idx = np.argsort(scores)[::-1][:k]
    return float(np.mean(y_true[top_idx]))


def recall_at_k(y_true: np.ndarray, scores: np.ndarray, k: int) -> float:
    y_true = np.asarray(y_true)
    scores = np.asarray(scores)
    positives = int(np.sum(y_true))
    if positives == 0:
        return 0.0
    n = len(y_true)
    if n == 0:
        return 0.0
    k = max(1, min(int(k), n))
    top_idx = np.argsort(scores)[::-1][:k]
    hits = int(np.sum(y_true[top_idx]))
    return float(hits / positives)


def top_n_detection(y_true: np.ndarray, scores: np.ndarray, n: int) -> dict:
    y_true = np.asarray(y_true)
    scores = np.asarray(scores)
    total = len(y_true)
    if total == 0:
        return {"n": 0, "hits": 0, "precision": 0.0, "recall": 0.0}

    n = max(1, min(int(n), total))
    top_idx = np.argsort(scores)[::-1][:n]
    hits = int(np.sum(y_true[top_idx]))
    positives = int(np.sum(y_true))

    return {
        "n": n,
        "hits": hits,
        "precision": float(hits / n),
        "recall": float(hits / positives) if positives > 0 else 0.0,
    }


def compute_fast_feature_importance(model, X_val_proc: np.ndarray, feature_names: list[str] | None) -> dict:
    from scipy.stats import spearmanr

    X = np.asarray(X_val_proc, dtype=np.float32)
    if X.ndim != 2 or X.shape[0] < 5 or X.shape[1] == 0:
        return {}

    scores = np.asarray(model.predict_proba(X)).reshape(-1)

    importances = {}
    for j in range(X.shape[1]):
        col = X[:, j]
        if np.unique(col).size < 2 or np.unique(scores).size < 2:
            corr = 0.0
        else:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                corr_val, _ = spearmanr(col, scores)
                corr = abs(float(corr_val)) if not np.isnan(corr_val) else 0.0

        fname = str(feature_names[j]) if feature_names is not None and j < len(feature_names) else f"feature_{j}"
        importances[fname] = corr

    total = float(sum(importances.values()))
    if total > 0:
        importances = {k: float(v / total) for k, v in importances.items()}
    return importances


def main():
    parser = argparse.ArgumentParser(description='Train COPOD for bank fraud detection')
    parser.add_argument('--dataset-path', required=True)
    parser.add_argument('--dataset-id', required=True)
    parser.add_argument('--job-id', required=True)
    parser.add_argument('--timestamp', required=True)
    parser.add_argument('--hyperparameters', required=True)
    parser.add_argument('--test-size', type=float, default=0.2)
    parser.add_argument('--random-seed', type=int, default=42)
    args = parser.parse_args()

    started_at = datetime.now()
    overall_start = time.time()

    logger.info("Starting COPOD training for dataset %s", args.dataset_id)

    original_stdout = sys.stdout
    sys.stdout = sys.stderr

    try:
        hp = load_hyperparameters(args.hyperparameters)

        model_name = hp.get('model_name', 'COPOD')
        selected_original_features = hp.get('selected_original_features', [])

        if not selected_original_features:
            raise ValueError("No original features selected for training")

        dataset_path = Path(args.dataset_path)
        if not dataset_path.exists():
            raise FileNotFoundError(f"Dataset not found: {dataset_path}")

        if dataset_path.suffix.lower() == '.parquet':
            df = pd.read_parquet(dataset_path)
        else:
            df = pd.read_csv(dataset_path)

        if not df.columns.is_unique:
            logger.warning("Duplicate column names detected. Keeping first occurrence.")
            df = df.loc[:, ~df.columns.duplicated(keep='first')]

        if 'fraud' not in df.columns:
            raise ValueError("Target column 'fraud' not found in dataset")

        if 'TX_Time' in df.columns:
            df['TX_Time'] = pd.to_datetime(df['TX_Time'], errors='coerce')
            before = len(df)
            df = df.dropna(subset=['TX_Time']).reset_index(drop=True)
            if len(df) < before:
                logger.warning("Dropped %d rows with invalid TX_Time", before - len(df))

        available_features = [
            f for f in selected_original_features
            if f in df.columns and f not in EXCLUDED_FEATURE_NAMES
        ]
        if not available_features:
            raise ValueError("None of the selected features are available in the dataset after excluding IDs")

        feature_cols_for_split = available_features.copy()
        if 'TX_Time' not in feature_cols_for_split and 'TX_Time' in df.columns:
            feature_cols_for_split.append('TX_Time')

        X = df[feature_cols_for_split].copy()
        y = df['fraud'].values
        df_processed = X.copy()
        df_processed['fraud'] = y

        excluded_cols = ['TX_Time', 'fraud']
        categorical_cols = df_processed.select_dtypes(exclude=[np.number, 'datetime64[ns]']).columns.tolist()
        categorical_cols = [c for c in categorical_cols if c not in excluded_cols]
        logger.info("Identified categorical columns: %s", categorical_cols)
        logger.info("Selected original features used for COPOD: %s", available_features)

        if 'TX_Time' not in df_processed.columns:
            raise ValueError("TX_Time column missing")

        df_train, df_val, df_test = temporal_split(
            df_processed, time_col='TX_Time', train_ratio=0.7, val_ratio=0.15
        )

        logger.info("Applying per-split preprocessing...")

        customer_region_categories = process_customer_postal_fit(df_train)
        df_train = process_customer_postal_transform(df_train, customer_region_categories)
        df_val = process_customer_postal_transform(df_val, customer_region_categories)
        df_test = process_customer_postal_transform(df_test, customer_region_categories)

        if 'Merchant_Postal_Code' in df_processed.columns:
            merchant_region_categories = process_merchant_postal_fit(df_train)
            df_train = process_merchant_postal_transform(df_train, merchant_region_categories)
            df_val = process_merchant_postal_transform(df_val, merchant_region_categories)
            df_test = process_merchant_postal_transform(df_test, merchant_region_categories)

        X_train = df_train.drop('fraud', axis=1)
        X_val = df_val.drop('fraud', axis=1)
        X_test = df_test.drop('fraud', axis=1)
        y_train = df_train['fraud'].values
        y_val = df_val['fraud'].values
        y_test = df_test['fraud'].values

        logger.info("Train: %d rows (fraud rate %.4f)", len(X_train), y_train.mean())
        logger.info("Val:   %d rows (fraud rate %.4f)", len(X_val), y_val.mean())
        logger.info("Test:  %d rows (fraud rate %.4f)", len(X_test), y_test.mean())

        preprocessor = Pipeline([
            ('onehot', SafeOneHotEncoder(categorical_cols=categorical_cols, include_raw_cats=True)),
            ('temporal', TemporalFeatureTransformer()),
            ('selector', TreeFeatureSelector()),
            ('scaler', SafeScaler(scaler_type='robust'))
        ])

        X_train_proc = preprocessor.fit_transform(X_train)
        X_val_proc = preprocessor.transform(X_val)
        X_test_proc = preprocessor.transform(X_test)

        preprocessor._customer_region_categories_ = list(customer_region_categories)
        if 'Merchant_Postal_Code' in df_processed.columns:
            preprocessor._merchant_region_categories_ = list(merchant_region_categories)

        if hasattr(X_train_proc, 'columns') and 'TX_Time' in X_train_proc.columns:
            X_train_proc = X_train_proc.drop('TX_Time', axis=1)
            X_val_proc = X_val_proc.drop('TX_Time', axis=1)
            X_test_proc = X_test_proc.drop('TX_Time', axis=1)

        feature_names = None
        try:
            selector = preprocessor.named_steps.get('selector')
            if selector is not None and hasattr(selector, 'get_feature_names_out'):
                feature_names = list(selector.get_feature_names_out())
                logger.info("Selected transformed feature count: %d", len(feature_names))
        except Exception as e:
            logger.warning("Could not extract selected feature names from selector: %s", e)

        if hasattr(X_train_proc, 'toarray'):
            X_train_proc = X_train_proc.toarray().astype(np.float32)
            X_val_proc = X_val_proc.toarray().astype(np.float32)
            X_test_proc = X_test_proc.toarray().astype(np.float32)

        X_train_proc = np.asarray(X_train_proc, dtype=np.float32)
        X_val_proc = np.asarray(X_val_proc, dtype=np.float32)
        X_test_proc = np.asarray(X_test_proc, dtype=np.float32)

        logger.info(
            "Data processed: Train=%s, Val=%s, Test=%s (%d features)",
            X_train_proc.shape, X_val_proc.shape, X_test_proc.shape, X_train_proc.shape[1]
        )

        default_contamination = max(0.001, min(0.02, float(y_train.mean())))
        contamination = float(hp.get('contamination', default_contamination))
        threshold_metric = hp.get('threshold_metric', 'fbeta')
        beta = hp.get('beta', 2.0)

        model = COPODFraudModel(
            contamination=contamination,
            threshold_metric=threshold_metric,
            beta=beta
        )

        logger.info("Fitting COPOD anomaly scorer...")
        train_start = time.time()
        model.fit(X_train_proc)
        train_time = time.time() - train_start
        final_model = model

        feature_importance = compute_fast_feature_importance(
            model=final_model,
            X_val_proc=X_val_proc,
            feature_names=feature_names,
        )
        logger.info("Computed fast correlation-based feature importance for %d features", len(feature_importance))

        logger.info("Computing VAL scores...")
        val_score_start = time.time()
        y_val_raw, y_val_score_norm = final_model.predict_score_and_proba(X_val_proc)
        logger.info("VAL scoring took %.1fs", time.time() - val_score_start)

        logger.info(
            "VAL RAW SCORE stats: min=%.6f max=%.6f mean=%.6f p95=%.6f",
            float(np.min(y_val_raw)),
            float(np.max(y_val_raw)),
            float(np.mean(y_val_raw)),
            float(np.percentile(y_val_raw, 95)),
        )
        logger.info(
            "VAL NORM SCORE stats: min=%.6f max=%.6f mean=%.6f p95=%.6f",
            float(np.min(y_val_score_norm)),
            float(np.max(y_val_score_norm)),
            float(np.mean(y_val_score_norm)),
            float(np.percentile(y_val_score_norm, 95)),
        )

        precision_vals, recall_vals, thresholds_pr = precision_recall_curve(y_val, y_val_score_norm)
        f1_scores = 2 * precision_vals[:-1] * recall_vals[:-1] / (precision_vals[:-1] + recall_vals[:-1] + 1e-9)

        if len(thresholds_pr) == 0:
            pr_threshold = 0.5
            best_f1 = 0.0
        else:
            best_idx = int(np.argmax(f1_scores))
            pr_threshold = float(thresholds_pr[best_idx])
            best_f1 = float(f1_scores[best_idx])

        logger.info("PR-based normalized-score threshold (max F1): %.4f (F1=%.4f)", pr_threshold, best_f1)

        fbeta_threshold, fbeta_score_val = find_threshold_by_fbeta(y_val, y_val_score_norm, beta=beta)
        logger.info("F-beta normalized-score threshold (β=%.1f): %.4f (score=%.4f)", beta, fbeta_threshold, fbeta_score_val)

        cost_threshold, min_cost = find_threshold_by_cost(
            y_val,
            y_val_score_norm,
            cost_fp=1.0,
            cost_fn=10.0
        )
        logger.info("Cost-based normalized-score threshold: %.4f (min_cost=%.0f)", cost_threshold, min_cost)

        user_threshold = hp.get('detection_threshold')
        if user_threshold is not None:
            final_threshold = float(user_threshold)
            logger.info("Using UI normalized-score threshold: %.4f", final_threshold)
        else:
            if threshold_metric == 'cost':
                final_threshold = float(cost_threshold)
                logger.info("No UI threshold, using cost-based normalized-score threshold: %.4f", final_threshold)
            elif threshold_metric == 'pr':
                final_threshold = float(pr_threshold)
                logger.info("No UI threshold, using PR-based normalized-score threshold: %.4f", final_threshold)
            else:
                final_threshold = float(fbeta_threshold)
                logger.info("No UI threshold, using F-beta normalized-score threshold: %.4f", final_threshold)

        final_model.threshold_ = float(final_threshold)

        test_score_start = time.time()
        y_test_raw, y_test_score_norm = final_model.predict_score_and_proba(X_test_proc)
        y_test_pred = (y_test_score_norm >= final_threshold).astype(int)
        logger.info("TEST scoring took %.1fs", time.time() - test_score_start)

        accuracy = accuracy_score(y_test, y_test_pred)
        precision = precision_score(y_test, y_test_pred, zero_division=0)
        recall = recall_score(y_test, y_test_pred, zero_division=0)
        f1 = f1_score(y_test, y_test_pred, zero_division=0)
        auc_roc = roc_auc_score(y_test, y_test_score_norm)
        pr_auc = average_precision_score(y_test, y_test_score_norm)
        cm = confusion_matrix(y_test, y_test_pred)

        k1 = max(1, int(round(len(y_test) * 0.01)))
        k5 = max(1, int(round(len(y_test) * 0.05)))
        k10 = max(1, int(round(len(y_test) * 0.10)))
        positives = int(np.sum(y_test))
        top_n = max(1, positives)

        p_at_1pct = precision_at_k(y_test, y_test_score_norm, k1)
        r_at_1pct = recall_at_k(y_test, y_test_score_norm, k1)
        p_at_5pct = precision_at_k(y_test, y_test_score_norm, k5)
        r_at_5pct = recall_at_k(y_test, y_test_score_norm, k5)
        p_at_10pct = precision_at_k(y_test, y_test_score_norm, k10)
        r_at_10pct = recall_at_k(y_test, y_test_score_norm, k10)
        top_n_stats = top_n_detection(y_test, y_test_score_norm, top_n)

        logger.info("Test metrics with normalized-score threshold %.4f:", final_threshold)
        logger.info("  Accuracy:      %.4f", accuracy)
        logger.info("  Precision:     %.4f", precision)
        logger.info("  Recall:        %.4f", recall)
        logger.info("  F1:            %.4f", f1)
        logger.info("  AUC-ROC:       %.4f", auc_roc)
        logger.info("  PR-AUC:        %.4f", pr_auc)

        timestamp = args.timestamp
        output_models_dir = Path("/tmp/fraud-work/output/models")
        output_pipelines_dir = Path("/tmp/fraud-work/output/pipelines")
        output_models_dir.mkdir(parents=True, exist_ok=True)
        output_pipelines_dir.mkdir(parents=True, exist_ok=True)

        pipeline_path = output_pipelines_dir / f"pipeline_{timestamp}.joblib"
        joblib.dump(preprocessor, pipeline_path)
        logger.info("Preprocessor saved to %s", pipeline_path)

        copod_inner = final_model._model
        if hasattr(copod_inner, 'decision_scores_'):
            del copod_inner.decision_scores_
        if hasattr(copod_inner, 'labels_'):
            del copod_inner.labels_
        if hasattr(copod_inner, 'X_train_') and copod_inner.X_train_ is not None:
            original_size = copod_inner.X_train_.nbytes
            copod_inner.X_train_ = copod_inner.X_train_.astype(np.float32)
            logger.info(
                "X_train_ converted float64->float32: %d MB -> %d MB",
                original_size // (1024 * 1024),
                copod_inner.X_train_.nbytes // (1024 * 1024),
            )

        base_filename = f"trained_model_{timestamp}"
        model_path = output_models_dir / f"{base_filename}.joblib"
        temp_model_path = output_models_dir / f"{base_filename}.tmp"

        try:
            joblib.dump(final_model, temp_model_path, compress=('gzip', 3))
            temp_model_path.rename(model_path)
            logger.info("Model saved to %s (%d bytes)", model_path, model_path.stat().st_size)
        finally:
            if temp_model_path.exists():
                temp_model_path.unlink()

        ended_at = datetime.now()
        total_duration = time.time() - overall_start

        result_raw = {
            'status': 'COMPLETED',
            'job_id': args.job_id,
            'dataset_id': args.dataset_id,
            'model_name': model_name,
            'model_path': str(model_path),
            'pipeline_path': str(pipeline_path),
            'selected_original_features': available_features,
            'training_mode': {
                'scoring': 'unsupervised_copod',
                'threshold_selection': 'supervised_on_validation_normalized_score_scale',
                'uses_labels_for_scoring': False,
                'uses_labels_for_threshold': True,
                'ui_threshold_used_directly': user_threshold is not None,
            },
            'hyperparameters_used': {
                'contamination': contamination,
                'threshold_metric': threshold_metric,
                'beta': beta,
            },
            'metrics': {
                'accuracy': float(accuracy),
                'precision': float(precision),
                'recall': float(recall),
                'f1_score': float(f1),
                'auc_roc': float(auc_roc),
                'pr_auc': float(pr_auc),
                'avg_anomaly_score': float(y_test_score_norm.mean()),
                'avg_raw_anomaly_score': float(y_test_raw.mean()),
            },
            'feature_importance': feature_importance,
            'ranking_metrics': {
                'precision_at_1pct': float(p_at_1pct),
                'recall_at_1pct': float(r_at_1pct),
                'precision_at_5pct': float(p_at_5pct),
                'recall_at_5pct': float(r_at_5pct),
                'precision_at_10pct': float(p_at_10pct),
                'recall_at_10pct': float(r_at_10pct),
                'top_n_detection': top_n_stats,
            },
            'confusion_matrix': {
                'true_negatives': int(cm[0, 0]),
                'false_positives': int(cm[0, 1]),
                'false_negatives': int(cm[1, 0]),
                'true_positives': int(cm[1, 1]),
            },
            'thresholds': {
                'pr_optimal': float(pr_threshold),
                'pr_f1': float(best_f1),
                'fbeta': float(fbeta_threshold),
                'fbeta_score': float(fbeta_score_val),
                'cost': float(cost_threshold),
                'min_cost': float(min_cost),
                'final': float(final_threshold),
                'val_raw_p95_debug': float(np.percentile(y_val_raw, 95)),
            },
            'training_stats': {
                'train_rows': int(len(X_train)),
                'val_rows': int(len(X_val)),
                'test_rows': int(len(X_test)),
                'fraud_rate_test': float(y_test.mean()),
                'train_time_seconds': float(train_time),
                'input_dim': int(X_train_proc.shape[1]),
                'test_positive_count': int(positives),
            },
            'start_time': started_at.isoformat(),
            'end_time': ended_at.isoformat(),
            'duration_seconds': float(total_duration),
        }

        result = make_json_safe(result_raw)

        sys.stdout = original_stdout
        print(json.dumps(result))

    except Exception as e:
        logger.exception("COPOD Training failed")
        sys.stdout = original_stdout
        error_result = {
            'status': 'FAILED',
            'error': str(e),
            'job_id': getattr(args, 'job_id', None),
            'dataset_id': getattr(args, 'dataset_id', None),
        }
        print(json.dumps(error_result))
        sys.exit(1)


if __name__ == '__main__':
    main()