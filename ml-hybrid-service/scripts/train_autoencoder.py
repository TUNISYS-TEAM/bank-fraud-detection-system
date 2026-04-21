"""
Train an Anomaly Autoencoder model.
Unsupervised anomaly detection based on reconstruction error (MSE).
Uses shared_ml components for preprocessing, splitting, and thresholding.
Includes temporal features, feature selection, scaling, and filtering of normal transactions.
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
    accuracy_score, precision_score, recall_score, f1_score,
    roc_auc_score, confusion_matrix, precision_recall_curve, average_precision_score
)
from sklearn.pipeline import Pipeline

sys.path.insert(0, '/app')

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
)

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
    stream=sys.stderr
)
logger = logging.getLogger(__name__)


# ------------------------------------------------------------------------------
# Helper for ID columns (not yet in shared_ml)
# ------------------------------------------------------------------------------
def extract_numeric_from_id(value):
    if pd.isna(value):
        return 0
    try:
        if isinstance(value, (int, float)):
            return float(value)
        s = str(value)
        if '-' in s:
            parts = s.split('-')
            if len(parts) > 1 and parts[1].replace('.', '', 1).isdigit():
                return float(parts[1])
        numbers = re.findall(r'\d+', s)
        if numbers:
            return float(numbers[0])
        return 0
    except Exception:
        return 0


def process_id_columns(df):
    """Extract numeric IDs – safe per-split operation."""
    df_processed = df.copy()
    id_columns = ['Account_Id', 'Device_Id']
    for col in id_columns:
        if col in df_processed.columns:
            df_processed[col] = df_processed[col].apply(extract_numeric_from_id).astype(np.float32)
    return df_processed


# ------------------------------------------------------------------------------
# JSON serialization helper
# ------------------------------------------------------------------------------
def make_json_safe(obj):
    """Convert numpy/pandas numeric types to Python native types."""
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


# ------------------------------------------------------------------------------
# Hyperparameter loader
# ------------------------------------------------------------------------------
def load_hyperparameters(hyperparam_arg: str) -> dict:
    try:
        return json.loads(hyperparam_arg)
    except json.JSONDecodeError:
        try:
            with open(hyperparam_arg, 'r') as f:
                return json.load(f)
        except Exception as e:
            raise ValueError(f"Could not load hyperparameters from '{hyperparam_arg}': {e}")


# ------------------------------------------------------------------------------
# Main
# ------------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description='Train Autoencoder for bank fraud detection')
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

    logger.info("Starting Autoencoder training for dataset %s", args.dataset_id)

    original_stdout = sys.stdout
    sys.stdout = sys.stderr

    try:
        hp = load_hyperparameters(args.hyperparameters)

        model_name = hp.get('model_name', 'AUTOENCODER')
        selected_original_features = hp.get('selected_original_features', [])
        all_features = hp.get('all_features', [])

        if not selected_original_features:
            raise ValueError("No original features selected for training")

        dataset_path = Path(args.dataset_path)
        if not dataset_path.exists():
            raise FileNotFoundError(f"Dataset not found: {dataset_path}")

        # Load data
        if dataset_path.suffix.lower() == '.parquet':
            df = pd.read_parquet(dataset_path)
        else:
            df = pd.read_csv(dataset_path)

        if not df.columns.is_unique:
            logger.warning("Duplicate column names detected. Keeping first occurrence.")
            df = df.loc[:, ~df.columns.duplicated(keep='first')]

        if 'TX_Time' in df.columns:
            df['TX_Time'] = pd.to_datetime(df['TX_Time'], errors='coerce')
            before = len(df)
            df = df.dropna(subset=['TX_Time']).reset_index(drop=True)
            if len(df) < before:
                logger.warning("Dropped %d rows with invalid TX_Time", before - len(df))

        # Keep only available features
        available_features = [f for f in selected_original_features if f in df.columns]
        if not available_features:
            raise ValueError("None of the selected features are available in the dataset")

        feature_cols_for_split = available_features.copy()
        if 'TX_Time' not in feature_cols_for_split and 'TX_Time' in df.columns:
            feature_cols_for_split.append('TX_Time')

        X = df[feature_cols_for_split].copy()
        y = df['fraud'].values
        df_processed = X.copy()
        df_processed['fraud'] = y

        # Identify categorical columns (non-numeric, non-datetime)
        excluded_cols = ['TX_Time', 'fraud', 'Account_Id', 'Device_Id']
        categorical_cols = df_processed.select_dtypes(exclude=[np.number, 'datetime64[ns]']).columns.tolist()
        categorical_cols = [c for c in categorical_cols if c not in excluded_cols]
        logger.info("Identified categorical columns: %s", categorical_cols)

        # ========== STEP 1: TEMPORAL SPLIT ==========
        if 'TX_Time' not in df_processed.columns:
            raise ValueError("TX_Time column missing")
        df_train, df_val, df_test = temporal_split(
            df_processed, time_col='TX_Time', train_ratio=0.7, val_ratio=0.15
        )

        # ========== STEP 2: PER‑SPLIT PREPROCESSING ==========
        logger.info("Applying per-split preprocessing...")

        # ID columns
        df_train = process_id_columns(df_train)
        df_val = process_id_columns(df_val)
        df_test = process_id_columns(df_test)

        # Customer postal codes
        customer_region_categories = process_customer_postal_fit(df_train)
        df_train = process_customer_postal_transform(df_train, customer_region_categories)
        df_val = process_customer_postal_transform(df_val, customer_region_categories)
        df_test = process_customer_postal_transform(df_test, customer_region_categories)

        # Separate features and target (keep TX_Time for temporal transformer)
        X_train = df_train.drop('fraud', axis=1)   # garde TX_Time
        X_val = df_val.drop('fraud', axis=1)
        X_test = df_test.drop('fraud', axis=1)
        y_train = df_train['fraud'].values
        y_val = df_val['fraud'].values
        y_test = df_test['fraud'].values

        # Ensure numeric (just in case)
        for X_set in [X_train, X_val, X_test]:
            for col in X_set.columns:
                if X_set[col].dtype == 'object':
                    X_set[col] = pd.to_numeric(X_set[col], errors='coerce').fillna(0)

        logger.info("Train: %d rows (fraud rate %.4f)", len(X_train), y_train.mean())
        logger.info("Val:   %d rows (fraud rate %.4f)", len(X_val), y_val.mean())
        logger.info("Test:  %d rows (fraud rate %.4f)", len(X_test), y_test.mean())

        # ========== STEP 3: PREPROCESSING PIPELINE (temporal + one-hot + selector + scaling) ==========
        preprocessor = Pipeline([
            ('temporal', TemporalFeatureTransformer()),
            ('onehot', SafeOneHotEncoder(categorical_cols=categorical_cols, include_raw_cats=True)),
            ('selector', TreeFeatureSelector()),
            ('scaler', SafeScaler(scaler_type='robust'))
        ])

        X_train_proc = preprocessor.fit_transform(X_train)
        X_val_proc = preprocessor.transform(X_val)
        X_test_proc = preprocessor.transform(X_test)

        # Drop TX_Time if still present (not needed for Autoencoder)
        if 'TX_Time' in X_train_proc.columns:
            X_train_proc = X_train_proc.drop('TX_Time', axis=1)
            X_val_proc = X_val_proc.drop('TX_Time', axis=1)
            X_test_proc = X_test_proc.drop('TX_Time', axis=1)

        # ========== STEP 4: AUTOENCODER MODEL (train only on normal transactions) ==========
        encoding_dim = hp.get('encoding_dim', 32)
        epochs = hp.get('epochs', 20)
        batch_size = hp.get('batch_size', 1024)
        threshold_metric = hp.get('threshold_metric', 'fbeta')
        beta = hp.get('beta', 2.0)

        model = AnomalyAutoencoder(
            encoding_dim=encoding_dim,
            epochs=epochs,
            batch_size=batch_size,
            threshold_metric=threshold_metric,
            beta=beta
        )

        # 🔥 CRITICAL: Filter only normal transactions for training the autoencoder
        normal_mask_train = y_train == 0
        X_train_normal = X_train_proc[normal_mask_train]
        logger.info(f"Training autoencoder on {len(X_train_normal)} normal transactions (out of {len(X_train)} total)")

        logger.info("Fitting Autoencoder on normal transactions...")
        train_start = time.time()
        model.fit(X_train_normal, y_train[normal_mask_train], X_val_proc, y_val)
        train_time = time.time() - train_start

        final_model = model

        # ========== STEP 5: THRESHOLD CALCULATIONS ==========
        y_val_proba_raw = final_model.predict_proba(X_val_proc)
        # Handle case where predict_proba returns a 1D array (probability of positive class)
        if y_val_proba_raw.ndim == 1:
            y_val_proba = y_val_proba_raw
        else:
            y_val_proba = y_val_proba_raw[:, 1]

        # PR-based (max F1)
        precision_vals, recall_vals, thresholds_pr = precision_recall_curve(y_val, y_val_proba)
        f1_scores = 2 * precision_vals[:-1] * recall_vals[:-1] / (precision_vals[:-1] + recall_vals[:-1] + 1e-9)
        best_idx = np.argmax(f1_scores)
        pr_threshold = thresholds_pr[best_idx]
        best_f1 = f1_scores[best_idx]
        logger.info("PR‑based threshold (max F1): %.4f (F1=%.4f)", pr_threshold, best_f1)

        # F-beta (β=2.0)
        fbeta_threshold, fbeta_score_val = find_threshold_by_fbeta(y_val, y_val_proba, beta=2.0)
        logger.info("F-beta (β=2.0): threshold=%.4f, score=%.4f", fbeta_threshold, fbeta_score_val)

        # Cost-based
        cost_threshold, min_cost = find_threshold_by_cost(y_val, y_val_proba, cost_fp=1.0, cost_fn=10.0)
        logger.info("Cost-based: threshold=%.4f, min_cost=%.0f", cost_threshold, min_cost)

        # User threshold from UI (if provided, otherwise use F-beta)
        user_threshold = hp.get('detection_threshold')
        if user_threshold is not None:
            final_threshold = float(user_threshold)
            logger.info("Using user‑supplied threshold from UI: %.4f", final_threshold)
            max_prob = y_val_proba.max()
            if final_threshold > max_prob:
                logger.warning(
                    "User threshold %.4f > max validation probability %.4f → will detect nothing.",
                    final_threshold, max_prob
                )
        else:
            final_threshold = fbeta_threshold
            logger.info("No UI threshold, using F-beta threshold: %.4f", final_threshold)

        # ========== STEP 6: TEST EVALUATION ==========
        y_test_proba_raw = final_model.predict_proba(X_test_proc)
        if y_test_proba_raw.ndim == 1:
            y_test_proba = y_test_proba_raw
        else:
            y_test_proba = y_test_proba_raw[:, 1]

        y_test_pred = (y_test_proba >= final_threshold).astype(int)

        accuracy = accuracy_score(y_test, y_test_pred)
        precision = precision_score(y_test, y_test_pred, zero_division=0)
        recall = recall_score(y_test, y_test_pred, zero_division=0)
        f1 = f1_score(y_test, y_test_pred, zero_division=0)
        auc_roc = roc_auc_score(y_test, y_test_proba)
        pr_auc = average_precision_score(y_test, y_test_proba)
        cm = confusion_matrix(y_test, y_test_pred)

        logger.info("Test metrics with threshold %.4f:", final_threshold)
        logger.info("  Accuracy:  %.4f", accuracy)
        logger.info("  Precision: %.4f", precision)
        logger.info("  Recall:    %.4f", recall)
        logger.info("  F1:        %.4f", f1)
        logger.info("  AUC-ROC:   %.4f", auc_roc)
        logger.info("  PR-AUC:    %.4f", pr_auc)

        # ========== STEP 7: SAVE ARTIFACTS ==========
        timestamp = args.timestamp
        output_models_dir = Path("/tmp/fraud-work/output/models")
        output_pipelines_dir = Path("/tmp/fraud-work/output/pipelines")
        output_models_dir.mkdir(parents=True, exist_ok=True)
        output_pipelines_dir.mkdir(parents=True, exist_ok=True)

        # Save preprocessor
        pipeline_path = output_pipelines_dir / f"pipeline_{timestamp}.joblib"
        joblib.dump(preprocessor, pipeline_path)
        logger.info("Preprocessor saved to %s", pipeline_path)

        # Save model
        base_filename = f"trained_model_{timestamp}"
        model_path = output_models_dir / f"{base_filename}.joblib"
        temp_model_path = output_models_dir / f"{base_filename}.tmp"
        try:
            model.save(temp_model_path)   # Autoencoder has its own save method
            temp_model_path.rename(model_path)
            logger.info("Model saved to %s (%d bytes)", model_path, model_path.stat().st_size)
        except Exception as e:
            logger.error("Failed to save model: %s", e)
            raise
        finally:
            if temp_model_path.exists():
                temp_model_path.unlink()

        # ========== STEP 8: FEATURE IMPORTANCE ==========
        feature_names = X_train.columns.tolist()
        try:
            feature_importance_map = model.get_feature_importance(feature_names=feature_names)
            feature_importance_map = dict(sorted(feature_importance_map.items(), key=lambda x: x[1], reverse=True))
        except Exception:
            logger.warning("Could not compute feature importance, skipping.")
            feature_importance_map = {}

        ended_at = datetime.now()
        total_duration = time.time() - overall_start

        # Build result
        result_raw = {
            'status': 'COMPLETED',
            'job_id': args.job_id,
            'dataset_id': args.dataset_id,
            'model_name': model_name,
            'model_path': str(model_path),
            'pipeline_path': str(pipeline_path),
            'selected_original_features': selected_original_features,
            'feature_importance': feature_importance_map,
            'hyperparameters_used': {
                'encoding_dim': encoding_dim,
                'epochs': epochs,
                'batch_size': batch_size,
                'threshold_metric': threshold_metric,
                'beta': beta,
                'all_features': all_features
            },
            'metrics': {
                'accuracy': float(accuracy),
                'precision': float(precision),
                'recall': float(recall),
                'f1_score': float(f1),
                'auc_roc': float(auc_roc),
                'pr_auc': float(pr_auc),
                'avg_fraud_score': float(y_test_proba.mean()),
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
            },
            'training_stats': {
                'train_rows': int(len(X_train)),
                'train_normal_rows': int(np.sum(normal_mask_train)),
                'val_rows': int(len(X_val)),
                'test_rows': int(len(X_test)),
                'fraud_rate_test': float(y_test.mean()),
                'train_time_seconds': float(train_time),
            },
            'start_time': started_at.isoformat(),
            'end_time': ended_at.isoformat(),
            'duration_seconds': float(total_duration),
        }

        result = make_json_safe(result_raw)

        sys.stdout = original_stdout
        print(json.dumps(result))

    except Exception as e:
        logger.exception("Autoencoder Training failed")
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