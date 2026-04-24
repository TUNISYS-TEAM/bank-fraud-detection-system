"""
Train a LightGBM model for bank fraud detection.
No data leakage – per-split preprocessing, dynamic thresholding, ONNX export.
Uses shared_ml components for splitting, postal codes, one-hot encoding, scaling,
temporal features, feature selection, and optimal thresholding.
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
from lightgbm import LGBMClassifier
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score, f1_score,
    roc_auc_score, confusion_matrix, precision_recall_curve, average_precision_score
)
from sklearn.pipeline import Pipeline

sys.path.insert(0, '/app')

# Shared ML imports
from shared_ml.evaluation.data_split import temporal_split
from shared_ml.evaluation.thresholding import (
    find_threshold_by_fbeta,
    find_threshold_by_cost,
)
from shared_ml.preprocessing.encoders import SafeOneHotEncoder
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
    df_processed = df.copy()
    id_columns = ['Account_Id', 'Device_Id']
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
            with open(hyperparam_arg, 'r') as f:
                return json.load(f)
        except Exception as e:
            raise ValueError(f"Could not load hyperparameters from '{hyperparam_arg}': {e}")


# ------------------------------------------------------------------------------
# JSON serialization helper
# ------------------------------------------------------------------------------
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


# ------------------------------------------------------------------------------
# ONNX export (fixed for LightGBM)
# ------------------------------------------------------------------------------
def export_lightgbm_to_onnx(model, output_path, input_dim):
    """Export LightGBM model to ONNX using onnxmltools (works reliably)."""
    try:
        from onnxmltools.convert import convert_lightgbm
        from onnxmltools.convert.common.data_types import FloatTensorType
        import onnx

        initial_type = [('float_input', FloatTensorType([None, input_dim]))]
        onnx_model = convert_lightgbm(model, initial_types=initial_type)
        onnx.save_model(onnx_model, str(output_path))
        logger.info("LightGBM ONNX exported via onnxmltools to: %s", output_path)
        return str(output_path)
    except Exception as e:
        logger.error("ONNX export failed: %s", e)
        return None


# ------------------------------------------------------------------------------
# Main
# ------------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description='Train LightGBM for bank fraud detection')
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

    logger.info("Starting LightGBM training for dataset %s", args.dataset_id)

    original_stdout = sys.stdout
    sys.stdout = sys.stderr

    try:
        hp = load_hyperparameters(args.hyperparameters)

        model_name = hp.get('model_name', 'LightGBM')
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
            df = df.loc[:, ~df.columns.duplicated(keep='first')]

        if 'TX_Time' in df.columns:
            df['TX_Time'] = pd.to_datetime(df['TX_Time'], errors='coerce')
            df = df.dropna(subset=['TX_Time']).reset_index(drop=True)

        available_features = [f for f in all_features if f in df.columns]
        if not available_features:
            raise ValueError("None of the required features are available")

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

        # ========== STEP 2: PER-SPLIT PREPROCESSING ==========
        logger.info("Applying per-split preprocessing...")
        df_train = process_id_columns(df_train)
        df_val = process_id_columns(df_val)
        df_test = process_id_columns(df_test)

        # Customer postal codes
        customer_region_categories = process_customer_postal_fit(df_train)
        df_train = process_customer_postal_transform(df_train, customer_region_categories)
        df_val = process_customer_postal_transform(df_val, customer_region_categories)
        df_test = process_customer_postal_transform(df_test, customer_region_categories)

        # Merchant postal codes (if present)
        if 'Merchant_Postal_Code' in df_processed.columns:
            merchant_region_categories = process_merchant_postal_fit(df_train)
            df_train = process_merchant_postal_transform(df_train, merchant_region_categories)
            df_val = process_merchant_postal_transform(df_val, merchant_region_categories)
            df_test = process_merchant_postal_transform(df_test, merchant_region_categories)

        # Separate features and target (keep TX_Time for temporal transformer)
        X_train = df_train.drop('fraud', axis=1)
        X_val = df_val.drop('fraud', axis=1)
        X_test = df_test.drop('fraud', axis=1)
        y_train = df_train['fraud'].values
        y_val = df_val['fraud'].values
        y_test = df_test['fraud'].values

        # Kept as requested
        for X_set in [X_train, X_val, X_test]:
            for col in X_set.columns:
                if X_set[col].dtype == 'object':
                    X_set[col] = pd.to_numeric(X_set[col], errors='coerce').fillna(0)

        logger.info("Train: %d rows (fraud rate %.4f)", len(X_train), y_train.mean())
        logger.info("Val:   %d rows (fraud rate %.4f)", len(X_val), y_val.mean())
        logger.info("Test:  %d rows (fraud rate %.4f)", len(X_test), y_test.mean())

        # ========== STEP 3: PREPROCESSING PIPELINE (aligned with RF) ==========
        preprocessor = Pipeline([
            ('onehot', SafeOneHotEncoder(categorical_cols=categorical_cols, include_raw_cats=True)),
            ('temporal', TemporalFeatureTransformer()),
            ('selector', TreeFeatureSelector()),
        ])

        X_train_proc = preprocessor.fit_transform(X_train)
        X_val_proc = preprocessor.transform(X_val)
        X_test_proc = preprocessor.transform(X_test)

        # Drop TX_Time if still present (defensive only)
        if hasattr(X_train_proc, 'columns') and 'TX_Time' in X_train_proc.columns:
            X_train_proc = X_train_proc.drop('TX_Time', axis=1)
            X_val_proc = X_val_proc.drop('TX_Time', axis=1)
            X_test_proc = X_test_proc.drop('TX_Time', axis=1)

        # Get feature names for importance
        try:
            feature_names = preprocessor.named_steps['onehot'].get_feature_names_out()
        except Exception:
            feature_names = [f"feature_{i}" for i in range(X_train_proc.shape[1])]

        # ========== STEP 4: LIGHTGBM ==========
        n_neg = (y_train == 0).sum()
        n_pos = (y_train == 1).sum()
        scale_pos_weight = n_neg / max(n_pos, 1)
        logger.info("scale_pos_weight = %.2f", scale_pos_weight)

        lgbm_params = {
            'objective': 'binary',
            'metric': 'auc',
            'random_state': args.random_seed,
            'n_jobs': -1,
            'verbosity': -1,
            'scale_pos_weight': scale_pos_weight,

            # Hyperparamètres principaux
            'n_estimators': hp.get('n_estimators', 300),
            'learning_rate': hp.get('learning_rate', 0.05),
            'num_leaves': hp.get('num_leaves', 31),
            'max_depth': hp.get('max_depth', -1),
            'min_child_samples': hp.get('min_child_samples', 20),

            # Priorité 1 : robustesse / généralisation
            'feature_fraction': hp.get('feature_fraction', 0.8),
            'bagging_fraction': hp.get('bagging_fraction', 0.8),
            'bagging_freq': hp.get('bagging_freq', 1),
            'lambda_l1': hp.get('lambda_l1', 0.0),
            'lambda_l2': hp.get('lambda_l2', 0.0),

            # Stabilité supplémentaire
            'min_split_gain': hp.get('min_split_gain', 0.0),

            # Early stopping
            'early_stopping_rounds': hp.get('early_stopping_rounds', 20),
        }
        logger.info("Training LightGBM with params: %s", lgbm_params)

        train_start = time.time()
        lgbm_model = LGBMClassifier(**lgbm_params)
        lgbm_model.fit(
            X_train_proc, y_train,
            eval_set=[(X_val_proc, y_val)],
            eval_metric='auc'
        )
        train_time = time.time() - train_start
        logger.info(
            "Base training complete in %.1f seconds, best iteration: %d",
            train_time,
            lgbm_model.best_iteration_
        )

        # No post-hoc calibration applied.
        # Probabilities are the raw outputs of LightGBM.
        final_model = lgbm_model

        # ========== STEP 5: THRESHOLDS ==========
        y_val_proba = final_model.predict_proba(X_val_proc)[:, 1]
        max_prob = y_val_proba.max()
        logger.info("Validation max probability: %.4f", max_prob)

        precision_vals, recall_vals, thresholds_pr = precision_recall_curve(y_val, y_val_proba)
        f1_scores = 2 * precision_vals[:-1] * recall_vals[:-1] / (precision_vals[:-1] + recall_vals[:-1] + 1e-9)
        best_idx = np.argmax(f1_scores)
        pr_threshold = thresholds_pr[best_idx]
        best_f1 = f1_scores[best_idx]
        logger.info("PR-based threshold (max F1): %.4f (F1=%.4f)", pr_threshold, best_f1)

        fbeta_threshold, fbeta_score_val = find_threshold_by_fbeta(y_val, y_val_proba, beta=2.0)
        logger.info("F-beta (β=2.0): threshold=%.4f, score=%.4f", fbeta_threshold, fbeta_score_val)

        cost_threshold, min_cost = find_threshold_by_cost(y_val, y_val_proba, cost_fp=1.0, cost_fn=10.0)
        logger.info("Cost-based: threshold=%.4f, min_cost=%.0f", cost_threshold, min_cost)

        user_threshold = hp.get('detection_threshold')
        if user_threshold is not None:
            final_threshold = float(user_threshold)
            logger.info("Using user-supplied threshold from UI: %.4f", final_threshold)
            if final_threshold > max_prob:
                logger.warning(
                    "User threshold %.4f > max validation prob %.4f → will detect nothing.",
                    final_threshold, max_prob
                )
        else:
            final_threshold = fbeta_threshold
            logger.info("No UI threshold, using F-beta threshold: %.4f", final_threshold)

        # ========== STEP 6: TEST EVALUATION ==========
        y_test_proba = final_model.predict_proba(X_test_proc)[:, 1]
        y_test_pred = (y_test_proba >= final_threshold).astype(int)

        accuracy = accuracy_score(y_test, y_test_pred)
        precision = precision_score(y_test, y_test_pred, zero_division=0)
        recall = recall_score(y_test, y_test_pred, zero_division=0)
        f1 = f1_score(y_test, y_test_pred, zero_division=0)
        auc_roc = roc_auc_score(y_test, y_test_proba)
        pr_auc = average_precision_score(y_test, y_test_proba)
        cm = confusion_matrix(y_test, y_test_pred)

        logger.info("Test metrics with threshold %.4f:", final_threshold)
        logger.info(
            "  Accuracy: %.4f, Precision: %.4f, Recall: %.4f, F1: %.4f, AUC-ROC: %.4f, PR-AUC: %.4f",
            accuracy, precision, recall, f1, auc_roc, pr_auc
        )

        # Feature importance
        importances = lgbm_model.feature_importances_
        feature_importance_map = dict(sorted(zip(feature_names, importances), key=lambda x: x[1], reverse=True))

        # ========== STEP 7: SAVE ARTIFACTS ==========
        timestamp = args.timestamp
        output_models_dir = Path("/tmp/fraud-work/output/models")
        output_onnx_dir = Path("/tmp/fraud-work/output/onnx")
        output_pipelines_dir = Path("/tmp/fraud-work/output/pipelines")
        for d in [output_models_dir, output_onnx_dir, output_pipelines_dir]:
            d.mkdir(parents=True, exist_ok=True)

        base_filename = f"trained_model_{timestamp}"
        model_path = output_models_dir / f"{base_filename}.joblib"
        pipeline_path = output_pipelines_dir / f"pipeline_{timestamp}.joblib"

        full_pipeline = Pipeline([('preprocessor', preprocessor), ('classifier', final_model)])

        temp_path = output_models_dir / f"{base_filename}.tmp"
        joblib.dump(full_pipeline, temp_path)
        temp_path.rename(model_path)
        logger.info("Model saved to %s (%d bytes)", model_path, model_path.stat().st_size)

        joblib.dump(preprocessor, pipeline_path)
        logger.info("Preprocessor saved to %s", pipeline_path)

        # ONNX export (base LightGBM only)
        onnx_file = output_onnx_dir / f"{base_filename}.onnx"
        onnx_path = export_lightgbm_to_onnx(lgbm_model, onnx_file, X_train_proc.shape[1])

        ended_at = datetime.now()
        total_duration = time.time() - overall_start

        result_raw = {
            'status': 'COMPLETED',
            'job_id': args.job_id,
            'dataset_id': args.dataset_id,
            'model_name': model_name,
            'onnx_path': str(onnx_path) if onnx_path else None,
            'model_path': str(model_path),
            'pipeline_path': str(pipeline_path),
            'selected_original_features': selected_original_features,
            'feature_importance': feature_importance_map,
            'hyperparameters_used': lgbm_params,
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
                'val_rows': int(len(X_val)),
                'test_rows': int(len(X_test)),
                'fraud_rate_test': float(y_test.mean()),
                'train_time_seconds': float(train_time),
                'best_iteration': lgbm_model.best_iteration_,
            },
            'start_time': started_at.isoformat(),
            'end_time': ended_at.isoformat(),
            'duration_seconds': float(total_duration),
        }

        result = make_json_safe(result_raw)

        sys.stdout = original_stdout
        print(json.dumps(result))

    except Exception as e:
        logger.exception("LightGBM training failed")
        sys.stdout = original_stdout
        print(json.dumps({
            'status': 'FAILED',
            'error': str(e),
            'job_id': getattr(args, 'job_id', None),
            'dataset_id': getattr(args, 'dataset_id', None),
        }))
        sys.exit(1)


if __name__ == '__main__':
    main()