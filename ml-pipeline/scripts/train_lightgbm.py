"""
Train a LightGBM model for bank fraud detection with:
- Proper class imbalance handling (scale_pos_weight).
- Early stopping to prevent overfitting.
- Probability calibration (Platt scaling) with saved sigmoid parameters.
- Robust threshold selection (PR‑curve max F1 as fallback).
- User‑supplied threshold from UI always used (with warning if above max_prob).
- ONNX export of the BASE model (calibration applied in post‑processing).
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
import traceback

import numpy as np
import pandas as pd
import joblib
from lightgbm import LGBMClassifier
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score, f1_score,
    roc_auc_score, confusion_matrix, precision_recall_curve, average_precision_score
)
from sklearn.pipeline import Pipeline
from sklearn.calibration import CalibratedClassifierCV
import onnx
import onnxmltools
from onnxmltools.convert.common.data_types import FloatTensorType

sys.path.insert(0, '/app/src')

from ml_pipeline.inference.thresholding import (
    find_threshold_by_fbeta,
    find_threshold_by_cost,
)
from ml_pipeline.pipeline.data_split import temporal_split
from ml_pipeline.transformers.behavioral.temporal_features import TemporalFeatureTransformer
from ml_pipeline.transformers.encoders.feature_selectors import TreeFeatureSelector
from ml_pipeline.transformers.scalers.monetary_scaler import SafeScaler
from ml_pipeline.utils.postal_codes import PostalCodeResolver

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
    stream=sys.stderr
)
logger = logging.getLogger(__name__)


# ──────────────────────────────────────────────────────────────────────────────
# Preprocessing helpers
# ──────────────────────────────────────────────────────────────────────────────

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


def process_id_columns(df: pd.DataFrame) -> pd.DataFrame:
    df_processed = df.copy()
    for col in ['Account_Id', 'Device_Id']:
        if col in df_processed.columns:
            logger.info("Processing ID column: %s", col)
            df_processed[col] = df_processed[col].apply(extract_numeric_from_id).astype(np.float32)
    return df_processed


def process_postal_codes(df: pd.DataFrame) -> pd.DataFrame:
    resolver = PostalCodeResolver()
    df_processed = df.copy()

    for pcol, rcol in [
        ('Customer_Postal_Code', 'Customer_Region'),
        ('Merchant_Postal_Code', 'Merchant_Region'),
    ]:
        if pcol in df_processed.columns:
            logger.info("Processing postal code column: %s", pcol)
            df_processed = resolver.enrich_dataframe(
                df_processed, pcol, add_gov=False, add_deleg=False, add_region=True
            )
            if 'region' in df_processed.columns:
                df_processed[rcol] = pd.Categorical(df_processed['region']).codes.astype(np.float32)
                df_processed = df_processed.drop([pcol, 'region'], axis=1, errors='ignore')

    return df_processed


def encode_categorical_columns(df: pd.DataFrame) -> pd.DataFrame:
    df_encoded = df.copy()
    exclude_columns = {'TX_Time', 'Account_Id', 'Device_Id', 'Customer_Region', 'Merchant_Region'}

    for col in df_encoded.columns:
        if col in exclude_columns:
            continue
        if df_encoded[col].dtype == 'object' or df_encoded[col].dtype.name == 'category':
            logger.info("Encoding categorical column: %s", col)
            df_encoded[col] = pd.Categorical(df_encoded[col]).codes.astype(np.float32)
            df_encoded[col] = df_encoded[col].replace(-1, 0)

    return df_encoded


def create_preprocessing_pipeline():
    return Pipeline([
        ('temporal', TemporalFeatureTransformer()),
        ('selector', TreeFeatureSelector()),
        ('scaler', SafeScaler(scaler_type='robust'))
    ])


# ──────────────────────────────────────────────────────────────────────────────
# Calibration helpers
# ──────────────────────────────────────────────────────────────────────────────

def extract_sigmoid_params(calibrated_model):
    """Extract Platt scaling parameters (a, b) from a CalibratedClassifierCV."""
    try:
        calibrated_classifiers = getattr(calibrated_model, 'calibrated_classifiers_', None)
        if not calibrated_classifiers:
            return None, None

        first = calibrated_classifiers[0]
        calibrator = None

        if hasattr(first, 'calibrators') and first.calibrators:
            calibrator = first.calibrators[0]
        elif hasattr(first, 'calibrator'):
            calibrator = first.calibrator
        elif hasattr(first, 'calibrators_') and first.calibrators_:
            calibrator = first.calibrators_[0]

        if calibrator is None:
            return None, None

        a = getattr(calibrator, 'a_', None)
        b = getattr(calibrator, 'b_', None)
        if a is None or b is None:
            return None, None

        if isinstance(a, (list, tuple, np.ndarray)):
            a = float(np.asarray(a).ravel()[0])
        else:
            a = float(a)

        if isinstance(b, (list, tuple, np.ndarray)):
            b = float(np.asarray(b).ravel()[0])
        else:
            b = float(b)

        return a, b
    except Exception as e:
        logger.warning("Failed to extract sigmoid params: %s", e)
        return None, None


# ──────────────────────────────────────────────────────────────────────────────
# ONNX export
# ──────────────────────────────────────────────────────────────────────────────

def export_lightgbm_to_onnx(model, output_path, input_dim):
    """Export BASE LightGBM model to ONNX."""
    try:
        try:
            from skl2onnx import convert_sklearn
            from skl2onnx.common.data_types import FloatTensorType as SkFloatTensorType
            logger.info("Trying ONNX export via skl2onnx...")
            initial_type = [('float_input', SkFloatTensorType([None, input_dim]))]
            onnx_model = convert_sklearn(model, initial_types=initial_type)
            onnx.save_model(onnx_model, str(output_path))
            logger.info("LightGBM ONNX model exported via skl2onnx to: %s", output_path)
            return str(output_path)
        except Exception as e1:
            logger.warning("skl2onnx export failed: %s", e1)

        try:
            logger.info("Trying ONNX export via onnxmltools...")
            initial_type = [('float_input', FloatTensorType([None, input_dim]))]
            onnx_model = onnxmltools.convert_lightgbm(model, initial_types=initial_type)
            onnx.save_model(onnx_model, str(output_path))
            logger.info("LightGBM ONNX model exported via onnxmltools to: %s", output_path)
            return str(output_path)
        except Exception as e2:
            logger.error("onnxmltools export also failed: %s", e2)
            logger.error("Full traceback:\n%s", traceback.format_exc())
            return None

    except Exception as e:
        logger.error("Global ONNX export failure: %s", e)
        logger.error("Full traceback:\n%s", traceback.format_exc())
        return None


# ──────────────────────────────────────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────────────────────────────────────

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
        with open(args.hyperparameters, 'r', encoding='utf-8') as f:
            hp = json.load(f)

        model_name = hp.get('model_name', 'LightGBM')
        selected_original_features = hp.get('selected_original_features', [])
        all_features = hp.get('all_features', [])

        if not selected_original_features:
            raise ValueError("No original features selected for training")
        if not all_features:
            raise ValueError("No features available for training")

        logger.info("Model name: %s", model_name)

        dataset_path = Path(args.dataset_path)
        if not dataset_path.exists():
            raise FileNotFoundError(f"Dataset not found: {dataset_path}")

        if dataset_path.suffix.lower() == '.parquet':
            df = pd.read_parquet(dataset_path)
        else:
            df = pd.read_csv(dataset_path)

        if 'fraud' not in df.columns:
            raise ValueError("Dataset must contain a 'fraud' column as target")
        if 'TX_Time' not in df.columns:
            raise ValueError("Dataset must contain 'TX_Time' for temporal split")

        # Manual preprocessing (ID extraction, postal codes, categorical encoding)
        df = process_id_columns(df)
        df = process_postal_codes(df)
        df = encode_categorical_columns(df)

        available_features = [f for f in all_features if f in df.columns]
        for extra in ['Customer_Region', 'Merchant_Region']:
            if extra in df.columns and extra not in available_features:
                available_features.append(extra)

        if not available_features:
            raise ValueError("None of the required features are available in the dataset")

        X = df[available_features].copy()
        y = df['fraud'].values

        df_processed = X.copy()
        df_processed['fraud'] = y

        # Temporal split – critical for fraud detection
        df_train, df_val, df_test = temporal_split(
            df_processed,
            train_ratio=0.7,
            val_ratio=0.15,
            time_col='TX_Time'
        )

        X_train = df_train.drop('fraud', axis=1)
        X_val   = df_val.drop('fraud', axis=1)
        X_test  = df_test.drop('fraud', axis=1)

        y_train = df_train['fraud'].values
        y_val   = df_val['fraud'].values
        y_test  = df_test['fraud'].values

        logger.info("Train: %d rows (fraud rate %.4f)", len(X_train), y_train.mean())
        logger.info("Val:   %d rows (fraud rate %.4f)", len(X_val), y_val.mean())
        logger.info("Test:  %d rows (fraud rate %.4f)", len(X_test), y_test.mean())

        # Preprocessing pipeline (fitted on train only)
        preprocessor = create_preprocessing_pipeline()
        X_train_proc = preprocessor.fit_transform(X_train)
        X_val_proc   = preprocessor.transform(X_val)
        X_test_proc  = preprocessor.transform(X_test)

        try:
            feature_names = preprocessor.get_feature_names_out()
        except Exception:
            feature_names = [f"feature_{i}" for i in range(X_train_proc.shape[1])]

        logger.info("Processed feature shape: %s", X_train_proc.shape)

        # ──────────────────────────────────────────────────────────────────────
        # LightGBM with early stopping and proper evaluation metric
        # ──────────────────────────────────────────────────────────────────────
        n_neg = (y_train == 0).sum()
        n_pos = (y_train == 1).sum()
        scale_pos_weight = n_neg / max(n_pos, 1)
        logger.info("scale_pos_weight = %.2f (neg/pos = %d/%d)", scale_pos_weight, n_neg, n_pos)

        lgbm_params = {
            'objective': 'binary',
            'metric': 'auc',                 # LightGBM doesn't have 'aucpr' natively; we'll use 'auc' for early stopping
            'random_state': args.random_seed,
            'n_jobs': -1,
            'verbosity': -1,
            'scale_pos_weight': scale_pos_weight,
            'n_estimators': hp.get('n_estimators', 200),
            'learning_rate': hp.get('learning_rate', 0.1),
            'num_leaves': hp.get('num_leaves', 31),
            'max_depth': hp.get('max_depth', -1),
            'min_child_samples': hp.get('min_child_samples', 50),
            'early_stopping_rounds': 20,
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
        logger.info("Base training complete in %.1f seconds", train_time)
        logger.info("Best iteration: %d", lgbm_model.best_iteration_)

        # ──────────────────────────────────────────────────────────────────────
        # Probability calibration (Platt scaling) on validation set
        # ──────────────────────────────────────────────────────────────────────
        logger.info("Applying probability calibration (Platt Scaling) using validation set...")
        calibrated_model = CalibratedClassifierCV(lgbm_model, cv='prefit', method='sigmoid')
        calibrated_model.fit(X_val_proc, y_val)

        final_model = calibrated_model

        calib_a, calib_b = extract_sigmoid_params(calibrated_model)
        if calib_a is not None and calib_b is not None:
            logger.info("Extracted sigmoid calibration parameters: a=%.6f, b=%.6f", calib_a, calib_b)
        else:
            logger.warning("Could not extract sigmoid calibration parameters")

        y_val_proba = final_model.predict_proba(X_val_proc)[:, 1]
        max_prob = y_val_proba.max()
        logger.info(
            "Probability stats (VAL) - Mean: %.4f | Min: %.4f | Max: %.4f",
            y_val_proba.mean(), y_val_proba.min(), max_prob
        )

        # ──────────────────────────────────────────────────────────────────────
        # Threshold selection (multiple strategies)
        # ──────────────────────────────────────────────────────────────────────
        # 1. Precision-Recall curve max F1 (primary fallback)
        precision_vals, recall_vals, thresholds_pr = precision_recall_curve(y_val, y_val_proba)
        f1_scores = 2 * precision_vals[:-1] * recall_vals[:-1] / (precision_vals[:-1] + recall_vals[:-1] + 1e-9)
        best_idx = np.argmax(f1_scores)
        pr_threshold = thresholds_pr[best_idx]
        best_f1 = f1_scores[best_idx]
        logger.info("PR‑based threshold: %.4f (max F1=%.4f)", pr_threshold, best_f1)

        # 2. F-beta (β=2.0)
        fbeta_threshold, fbeta_score_val = find_threshold_by_fbeta(y_val, y_val_proba, beta=2.0)
        logger.info("F-beta (β=2.0): threshold=%.4f, score=%.4f", fbeta_threshold, fbeta_score_val)

        # 3. Cost-based
        cost_threshold, min_cost = find_threshold_by_cost(y_val, y_val_proba, cost_fp=1.0, cost_fn=10.0)
        logger.info("Cost-based: threshold=%.4f, min_cost=%.0f", cost_threshold, min_cost)

        # 4. PR-AUC
        pr_auc = average_precision_score(y_val, y_val_proba)
        logger.info("PR-AUC (val): %.4f", pr_auc)

        user_threshold = hp.get('detection_threshold')
        if user_threshold is not None:
            final_threshold = float(user_threshold)
            logger.info("Using user-supplied threshold from UI: %.4f", final_threshold)
            if final_threshold > max_prob:
                logger.warning(
                    "User threshold %.4f is above maximum predicted probability %.4f. "
                    "This will result in zero detections. Consider lowering the threshold.",
                    final_threshold, max_prob
                )
        else:
            final_threshold = float(pr_threshold)
            logger.info("No UI threshold provided, using PR‑based threshold: %.4f", final_threshold)

        # ──────────────────────────────────────────────────────────────────────
        # Test set evaluation
        # ──────────────────────────────────────────────────────────────────────
        y_test_proba = final_model.predict_proba(X_test_proc)[:, 1]
        logger.info(
            "Probability stats (TEST) - Mean: %.4f | Min: %.4f | Max: %.4f",
            y_test_proba.mean(), y_test_proba.min(), y_test_proba.max()
        )

        y_test_pred = (y_test_proba >= final_threshold).astype(int)
        logger.info("Predictions matching final threshold (%.4f): %d", final_threshold, int(y_test_pred.sum()))

        accuracy  = accuracy_score(y_test, y_test_pred)
        precision = precision_score(y_test, y_test_pred, zero_division=0)
        recall    = recall_score(y_test, y_test_pred, zero_division=0)
        f1        = f1_score(y_test, y_test_pred, zero_division=0)
        auc_roc   = roc_auc_score(y_test, y_test_proba)
        pr_auc_test = average_precision_score(y_test, y_test_proba)
        cm        = confusion_matrix(y_test, y_test_pred)

        logger.info("Test metrics:")
        logger.info("  Accuracy:  %.4f", accuracy)
        logger.info("  Precision: %.4f", precision)
        logger.info("  Recall:    %.4f", recall)
        logger.info("  F1:        %.4f", f1)
        logger.info("  AUC-ROC:   %.4f", auc_roc)
        logger.info("  PR-AUC:    %.4f", pr_auc_test)

        # Feature importance
        importances = lgbm_model.feature_importances_
        feature_importance_map = dict(
            sorted(
                {n: float(v) for n, v in zip(feature_names, importances)}.items(),
                key=lambda x: x[1],
                reverse=True
            )
        )

        calibration_sigmoid = None
        if calib_a is not None and calib_b is not None:
            calibration_sigmoid = {"a": calib_a, "b": calib_b}

        # ──────────────────────────────────────────────────────────────────────
        # Save artifacts
        # ──────────────────────────────────────────────────────────────────────
        full_pipeline = Pipeline([
            ('preprocessor', preprocessor),
            ('classifier', final_model)
        ])

        if calibration_sigmoid is not None:
            full_pipeline.calibration_sigmoid = calibration_sigmoid
            preprocessor.calibration_sigmoid = calibration_sigmoid

        timestamp = args.timestamp
        output_models_dir    = Path("/app/output/models")
        output_onnx_dir      = Path("/app/output/onnx")
        output_pipelines_dir = Path("/app/output/pipelines")

        for d in [output_models_dir, output_onnx_dir, output_pipelines_dir]:
            d.mkdir(parents=True, exist_ok=True)

        base_filename = f"trained_model_{timestamp}"

        model_path = output_models_dir / f"{base_filename}.joblib"
        joblib.dump(full_pipeline, model_path)

        pipeline_path = output_pipelines_dir / f"pipeline_{timestamp}.joblib"
        joblib.dump(preprocessor, pipeline_path)

        logger.info("Exporting BASE LightGBM model to ONNX (uncalibrated)...")
        onnx_file = output_onnx_dir / f"{base_filename}.onnx"
        input_dim = X_train_proc.shape[1]

        onnx_path = export_lightgbm_to_onnx(
            model=lgbm_model,
            output_path=onnx_file,
            input_dim=input_dim
        )

        ended_at = datetime.now()
        total_duration = time.time() - overall_start

        sys.stdout = original_stdout
        result = {
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
                'pr_auc': float(pr_auc_test),
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
                'best_iteration': int(lgbm_model.best_iteration_),
            },
            'calibration_sigmoid': calibration_sigmoid,
            'start_time': started_at.isoformat(),
            'end_time': ended_at.isoformat(),
            'duration_seconds': float(total_duration),
        }
        print(json.dumps(result))

    except Exception as e:
        logger.exception("LightGBM training failed")
        error_msg = str(e)
        error_traceback = traceback.format_exc()
        sys.stdout = original_stdout
        print(json.dumps({
            'status': 'FAILED',
            'error': error_msg,
            'traceback': error_traceback,
            'job_id': getattr(args, 'job_id', None),
            'dataset_id': getattr(args, 'dataset_id', None),
        }))
        sys.exit(1)


if __name__ == '__main__':
    main()