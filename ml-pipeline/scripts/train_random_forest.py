"""
Train a Random Forest model with cost-sensitive learning and dynamic thresholding.

Dynamic categorical encoding: only columns present in training data are encoded.
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
    accuracy_score, precision_score, recall_score, f1_score,
    roc_auc_score, confusion_matrix, precision_recall_curve
)
from sklearn.pipeline import Pipeline
import onnx
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

sys.path.insert(0, '/app/src')

from ml_pipeline.inference.thresholding import (
    find_threshold_by_fbeta,
    find_threshold_by_cost,
)
from ml_pipeline.pipeline.data_split import temporal_split
from ml_pipeline.transformers.behavioral.temporal_features import TemporalFeatureTransformer
from ml_pipeline.transformers.encoders.categorical_encoder import SafeOneHotEncoder
from ml_pipeline.transformers.encoders.feature_selectors import TreeFeatureSelector
from ml_pipeline.transformers.scalers.monetary_scaler import SafeScaler
from ml_pipeline.utils.postal_codes import PostalCodeResolver

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
    stream=sys.stderr
)
logger = logging.getLogger(__name__)


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
            logger.info("Processing ID column with substr: %s", col)
            df_processed[col] = df_processed[col].apply(extract_numeric_from_id).astype(np.float32)

    return df_processed


def process_postal_codes(df):
    resolver = PostalCodeResolver()
    df_processed = df.copy()

    if 'Customer_Postal_Code' in df_processed.columns:
        logger.info("Processing Customer_Postal_Code...")
        df_processed = resolver.enrich_dataframe(
            df_processed, 'Customer_Postal_Code', add_gov=False, add_deleg=False, add_region=True
        )
        df_processed['Customer_Region'] = pd.Categorical(df_processed['region']).codes.astype(np.float32)
        df_processed = df_processed.drop(['Customer_Postal_Code', 'region'], axis=1)

    if 'Merchant_Postal_Code' in df_processed.columns:
        logger.info("Processing Merchant_Postal_Code...")
        df_processed = resolver.enrich_dataframe(
            df_processed, 'Merchant_Postal_Code', add_gov=False, add_deleg=False, add_region=True
        )
        df_processed['Merchant_Region'] = pd.Categorical(df_processed['region']).codes.astype(np.float32)
        df_processed = df_processed.drop(['Merchant_Postal_Code', 'region'], axis=1)

    return df_processed


def identify_categorical_columns(df: pd.DataFrame) -> list[str]:
    """
    Dynamically identify categorical columns present in the DataFrame.
    Priority list of expected categorical columns; only those present are returned.
    """
    expected_cats = [
        'category', 'Channel',
        'client_usual_region', 'client_usual_channel', 'client_usual_category'
    ]
    # Also include any object/category dtype columns not explicitly excluded
    exclude = ['TX_Time', 'Account_Id', 'Device_Id', 'Customer_Region', 'Merchant_Region']
    categorical_cols = []
    for col in df.columns:
        if col in exclude:
            continue
        if col in expected_cats:
            categorical_cols.append(col)
        elif df[col].dtype == 'object' or df[col].dtype.name == 'category':
            categorical_cols.append(col)
    return categorical_cols


def create_preprocessing_pipeline(categorical_columns: list[str]) -> Pipeline:
    """
    Build a preprocessing pipeline using SafeOneHotEncoder, TemporalFeatureTransformer,
    TreeFeatureSelector, and SafeScaler.
    Only the provided categorical columns are encoded.
    """
    return Pipeline([
        ('onehot', SafeOneHotEncoder(
            categorical_cols=categorical_columns,
            include_raw_cats=True
        )),
        ('temporal', TemporalFeatureTransformer()),
        ('selector', TreeFeatureSelector()),
        ('scaler', SafeScaler(scaler_type='robust'))
    ])


def export_to_onnx_direct(model, output_path, input_dim):
    try:
        initial_type = [('float_input', FloatTensorType([None, input_dim]))]
        onnx_model = convert_sklearn(model, initial_types=initial_type)
        onnx.save_model(onnx_model, str(output_path))
        logger.info("ONNX model exported successfully to: %s", output_path)
        return str(output_path)
    except Exception as e:
        logger.error("ONNX export failed: %s", e)
        return None


def main():
    parser = argparse.ArgumentParser(description='Train Random Forest with dynamic categorical encoding')
    parser.add_argument('--dataset-path', required=True, help='Path to dataset (Parquet/CSV)')
    parser.add_argument('--dataset-id', required=True, help='Dataset UUID')
    parser.add_argument('--job-id', required=True, help='Job UUID')
    parser.add_argument('--timestamp', required=True, help='Timestamp for file naming')
    parser.add_argument('--hyperparameters', required=True, help='Path to hyperparameters JSON')
    parser.add_argument('--test-size', type=float, default=0.2, help='Ignored; temporal split used')
    parser.add_argument('--random-seed', type=int, default=42, help='Random seed')
    args = parser.parse_args()

    started_at = datetime.now()
    overall_start = time.time()

    logger.info("Starting Random Forest training for dataset %s", args.dataset_id)

    original_stdout = sys.stdout
    sys.stdout = sys.stderr

    try:
        with open(args.hyperparameters, 'r', encoding='utf-8') as f:
            hp = json.load(f)

        model_name = hp.get('model_name', 'Random Forest')
        selected_original_features = hp.get('selected_original_features', [])
        all_features = hp.get('all_features', [])

        if not selected_original_features:
            raise ValueError("No original features selected for training")
        if not all_features:
            raise ValueError("No features available for training")

        logger.info("Model name: %s", model_name)
        logger.info("Selected original features (%d): %s", len(selected_original_features), selected_original_features)
        logger.info("Total features for training (%d): %s", len(all_features), all_features)

        logger.info("Loading dataset from %s", args.dataset_path)
        dataset_path = Path(args.dataset_path)
        if not dataset_path.exists():
            raise FileNotFoundError(f"Dataset not found: {dataset_path}")

        if dataset_path.suffix.lower() == '.parquet':
            df = pd.read_parquet(dataset_path)
        else:
            df = pd.read_csv(dataset_path)

        logger.info("Loaded %d rows, columns: %s", len(df), list(df.columns))

        # Manual preprocessing – NO categorical encoding here, only ID extraction and postal codes
        logger.info("Applying manual preprocessing...")
        df = process_id_columns(df)
        df = process_postal_codes(df)
        logger.info("Manual preprocessing complete")

        if 'fraud' not in df.columns:
            raise ValueError("Dataset must contain a 'fraud' column as target")
        if 'TX_Time' not in df.columns:
            raise ValueError("Dataset must contain 'TX_Time' for temporal split")

        available_features = [f for f in all_features if f in df.columns]

        if 'Customer_Region' in df.columns and 'Customer_Region' not in available_features:
            available_features.append('Customer_Region')
        if 'Merchant_Region' in df.columns and 'Merchant_Region' not in available_features:
            available_features.append('Merchant_Region')

        if not available_features:
            raise ValueError("None of the required features are available in the dataset")

        X = df[available_features].copy()
        y = df['fraud'].values

        df_processed = X.copy()
        df_processed['fraud'] = y

        # -----------------------------------------------------------------
        # CRITICAL: Temporal split BEFORE any categorical encoding
        # -----------------------------------------------------------------
        logger.info("Performing temporal split...")
        df_train, df_val, df_test = temporal_split(
            df_processed,
            train_ratio=0.7,
            val_ratio=0.15,
            time_col='TX_Time'
        )

        X_train = df_train.drop('fraud', axis=1)
        X_val = df_val.drop('fraud', axis=1)
        X_test = df_test.drop('fraud', axis=1)

        y_train = df_train['fraud'].values
        y_val = df_val['fraud'].values
        y_test = df_test['fraud'].values

        logger.info("Train: %d rows, Val: %d rows, Test: %d rows", len(X_train), len(X_val), len(X_test))
        logger.info("Fraud rate — train: %.4f, val: %.4f, test: %.4f", y_train.mean(), y_val.mean(), y_test.mean())

        # -----------------------------------------------------------------
        # Dynamically identify categorical columns present in training data
        # -----------------------------------------------------------------
        categorical_columns = identify_categorical_columns(X_train)
        logger.info("Categorical columns to encode: %s", categorical_columns)

        # -----------------------------------------------------------------
        # Build and fit preprocessing pipeline ON TRAINING DATA ONLY
        # -----------------------------------------------------------------
        logger.info("Creating and fitting preprocessing pipeline...")
        preprocessor = create_preprocessing_pipeline(categorical_columns)

        X_train_processed = preprocessor.fit_transform(X_train)
        X_val_processed = preprocessor.transform(X_val)
        X_test_processed = preprocessor.transform(X_test)

        try:
            feature_names = preprocessor.get_feature_names_out()
        except Exception:
            feature_names = [f"feature_{i}" for i in range(X_train_processed.shape[1])]

        logger.info("Processed feature shape: %s", X_train_processed.shape)

        # -----------------------------------------------------------------
        # Cost‑sensitive configuration
        # -----------------------------------------------------------------
        fraud_weight = hp.get('fraud_weight')
        if fraud_weight is None:
            fraud_ratio = y_train.mean()
            if fraud_ratio > 0:
                fraud_weight = min(1.0 / fraud_ratio, 50.0)
            else:
                fraud_weight = 30.0
        logger.info("Using fraud class weight: %.1f", fraud_weight)

        class_weight = {0: 1, 1: fraud_weight}

        fixed_params = {
            'min_samples_split': 20,
            'min_samples_leaf': 10,
            'class_weight': class_weight,
            'random_state': args.random_seed,
            'n_jobs': -1,
            'verbose': 0,
            'max_features': 'sqrt',
            'bootstrap': True,
            'max_samples': 0.8
        }

        user_params = {
            'n_estimators': hp.get('n_estimators', 100),
            'max_depth': hp.get('max_depth', 15),
        }

        rf_params = {**fixed_params, **user_params}
        logger.info("Training Random Forest with params: %s", rf_params)

        train_start = time.time()
        rf_model = RandomForestClassifier(**rf_params)
        rf_model.fit(X_train_processed, y_train)
        train_time = time.time() - train_start

        y_val_proba = rf_model.predict_proba(X_val_processed)[:, 1]

        max_prob = y_val_proba.max()
        logger.info("Maximum predicted fraud probability on validation set: %.4f", max_prob)
        if max_prob < 0.3:
            logger.warning("Model is under‑confident (max prob < 0.3). Consider increasing fraud weight or tuning hyperparameters.")

        # -----------------------------------------------------------------
        # Robust threshold from Precision-Recall curve (primary fallback)
        # -----------------------------------------------------------------
        logger.info("Computing optimal threshold from Precision-Recall curve...")
        precision_vals, recall_vals, thresholds_pr = precision_recall_curve(y_val, y_val_proba)
        f1_scores = 2 * precision_vals[:-1] * recall_vals[:-1] / (precision_vals[:-1] + recall_vals[:-1] + 1e-9)
        best_idx = np.argmax(f1_scores)
        pr_threshold = thresholds_pr[best_idx]
        best_f1 = f1_scores[best_idx]
        logger.info("  PR‑based threshold: %.4f (max F1=%.4f)", pr_threshold, best_f1)

        # Also compute F-beta and cost for reference
        fbeta_threshold, fbeta_score_val = find_threshold_by_fbeta(y_val, y_val_proba, beta=2.0)
        logger.info("  F-beta (β=2.0): threshold=%.4f, score=%.4f", fbeta_threshold, fbeta_score_val)

        cost_threshold, min_cost = find_threshold_by_cost(y_val, y_val_proba, cost_fp=1.0, cost_fn=10.0)
        logger.info("  Cost-based: threshold=%.4f, min_cost=%.0f", cost_threshold, min_cost)

        # User threshold from UI takes precedence; else use PR threshold
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

        # Test set evaluation
        y_test_proba = rf_model.predict_proba(X_test_processed)[:, 1]
        y_test_pred = (y_test_proba > final_threshold).astype(int)

        accuracy = accuracy_score(y_test, y_test_pred)
        precision = precision_score(y_test, y_test_pred, zero_division=0)
        recall = recall_score(y_test, y_test_pred, zero_division=0)
        f1 = f1_score(y_test, y_test_pred, zero_division=0)
        auc_roc = roc_auc_score(y_test, y_test_proba)
        cm = confusion_matrix(y_test, y_test_pred)

        logger.info("Test metrics with threshold %.4f:", final_threshold)
        logger.info("  Accuracy: %.4f", accuracy)
        logger.info("  Precision: %.4f", precision)
        logger.info("  Recall: %.4f", recall)
        logger.info("  F1: %.4f", f1)
        logger.info("  AUC-ROC: %.4f", auc_roc)

        # Feature importance
        importances = rf_model.feature_importances_
        feature_importance_map = {}
        for name, importance in zip(feature_names, importances):
            feature_importance_map[name] = float(importance)
        feature_importance_map = dict(
            sorted(feature_importance_map.items(), key=lambda item: item[1], reverse=True)
        )

        # -----------------------------------------------------------------
        # Save separate files (compatible with existing inference script)
        # -----------------------------------------------------------------
        timestamp = args.timestamp
        output_models_dir = Path("/app/output/models")
        output_onnx_dir = Path("/app/output/onnx")
        output_pipelines_dir = Path("/app/output/pipelines")

        output_models_dir.mkdir(parents=True, exist_ok=True)
        output_onnx_dir.mkdir(parents=True, exist_ok=True)
        output_pipelines_dir.mkdir(parents=True, exist_ok=True)

        base_filename = f"trained_model_{timestamp}"

        # 1. Save preprocessor (standalone)
        pipeline_filename = f"pipeline_{timestamp}.joblib"
        pipeline_path = output_pipelines_dir / pipeline_filename
        joblib.dump(preprocessor, pipeline_path)
        logger.info("Preprocessor saved to %s", pipeline_path)

        # 2. Save classifier
        model_filename = f"{base_filename}.joblib"
        model_path = output_models_dir / model_filename
        joblib.dump(rf_model, model_path)
        logger.info("Classifier saved to %s", model_path)

        # 3. Export ONNX (classifier only)
        logger.info("Exporting classifier to ONNX...")
        onnx_filename = f"{base_filename}.onnx"
        onnx_file = output_onnx_dir / onnx_filename
        input_dim = X_train_processed.shape[1]

        onnx_path = export_to_onnx_direct(
            model=rf_model,
            output_path=onnx_file,
            input_dim=input_dim
        )

        if not onnx_path:
            raise RuntimeError("ONNX export failed")

        ended_at = datetime.now()
        total_duration = time.time() - overall_start

        sys.stdout = original_stdout
        result = {
            'status': 'COMPLETED',
            'job_id': args.job_id,
            'dataset_id': args.dataset_id,
            'model_name': model_name,
            'onnx_path': str(onnx_path),
            'model_path': str(model_path),
            'pipeline_path': str(pipeline_path),
            'selected_original_features': selected_original_features,
            'feature_importance': feature_importance_map,
            'hyperparameters_used': rf_params,
            'metrics': {
                'accuracy': float(accuracy),
                'precision': float(precision),
                'recall': float(recall),
                'f1_score': float(f1),
                'auc_roc': float(auc_roc),
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
            },
            'start_time': started_at.isoformat(),
            'end_time': ended_at.isoformat(),
            'duration_seconds': float(total_duration),
        }
        print(json.dumps(result))

    except Exception as e:
        logger.exception("Training failed")
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