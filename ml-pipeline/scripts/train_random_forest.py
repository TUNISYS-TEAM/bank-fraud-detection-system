"""
Train a Random Forest model for bank fraud detection.
No data leakage – per‑split preprocessing, cost‑sensitive learning, dynamic thresholding.
Handles both Customer_Postal_Code and Merchant_Postal_Code.
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
    roc_auc_score, confusion_matrix, precision_recall_curve, average_precision_score
)
from sklearn.pipeline import Pipeline
from sklearn.compose import ColumnTransformer
from sklearn.preprocessing import RobustScaler
import onnx
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

sys.path.insert(0, '/app/src')

from ml_pipeline.inference.thresholding import (
    find_threshold_by_fbeta,
    find_threshold_by_cost,
)
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

# ------------------------------------------------------------------------------
# Global resolver – loaded once
# ------------------------------------------------------------------------------
_RESOLVER = None

def get_resolver():
    global _RESOLVER
    if _RESOLVER is None:
        logger.info("Loading PostalCodeResolver (once)")
        _RESOLVER = PostalCodeResolver()
    return _RESOLVER

# ------------------------------------------------------------------------------
# Preprocessing helpers (NO data leakage – fit on train only)
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


def process_postal_codes_fit(df_train):
    """Fit region mapping for Customer_Postal_Code on training data only."""
    resolver = get_resolver()
    logger.info("Enriching training data with Customer_Postal_Code (fit)")
    df_enriched = resolver.enrich_dataframe(
        df_train, 'Customer_Postal_Code', add_gov=False, add_deleg=False, add_region=True
    )
    region_categories = df_enriched['region'].astype('category').cat.categories
    logger.info("Customer region categories (fit): %d unique", len(region_categories))
    return region_categories


def process_customer_postal_transform(df, region_categories):
    """Transform Customer_Postal_Code using pre‑fitted region categories."""
    resolver = get_resolver()
    df_enriched = resolver.enrich_dataframe(
        df, 'Customer_Postal_Code', add_gov=False, add_deleg=False, add_region=True
    )
    cat_region = pd.Categorical(df_enriched['region'], categories=region_categories)
    df_enriched['Customer_Region'] = cat_region.codes.astype(np.float32)
    df_enriched = df_enriched.drop(['Customer_Postal_Code', 'region'], axis=1)
    return df_enriched


def process_merchant_postal_fit(df_train):
    """Fit region mapping for Merchant_Postal_Code on training data only."""
    resolver = get_resolver()
    logger.info("Enriching training data with Merchant_Postal_Code (fit)")
    df_enriched = resolver.enrich_dataframe(
        df_train, 'Merchant_Postal_Code', add_gov=False, add_deleg=False, add_region=True
    )
    merchant_region_categories = df_enriched['region'].astype('category').cat.categories
    logger.info("Merchant region categories (fit): %d unique", len(merchant_region_categories))
    return merchant_region_categories


def process_merchant_postal_transform(df, merchant_region_categories):
    """Transform Merchant_Postal_Code using pre‑fitted region categories."""
    resolver = get_resolver()
    df_enriched = resolver.enrich_dataframe(
        df, 'Merchant_Postal_Code', add_gov=False, add_deleg=False, add_region=True
    )
    cat_region = pd.Categorical(df_enriched['region'], categories=merchant_region_categories)
    df_enriched['Merchant_Region'] = cat_region.codes.astype(np.float32)
    df_enriched = df_enriched.drop(['Merchant_Postal_Code', 'region'], axis=1)
    return df_enriched


def identify_categorical_columns(df: pd.DataFrame) -> list[str]:
    """
    Dynamically identify categorical columns present in the DataFrame.
    Only columns that are object/category type (excluding known numeric columns).
    """
    expected_cats = ['category', 'Channel', 'client_usual_region', 'client_usual_channel', 'client_usual_category']
    exclude = {'TX_Time', 'Account_Id', 'Device_Id', 'Customer_Region', 'Merchant_Region'}
    categorical_cols = []
    for col in df.columns:
        if col in exclude:
            continue
        if col in expected_cats or df[col].dtype == 'object' or df[col].dtype.name == 'category':
            categorical_cols.append(col)
    return categorical_cols


def create_preprocessing_pipeline(categorical_columns: list[str]) -> Pipeline:
    """Build preprocessing pipeline: one‑hot encoding, temporal features, feature selection, scaling."""
    return Pipeline([
        ('onehot', SafeOneHotEncoder(categorical_cols=categorical_columns, include_raw_cats=True)),
        ('temporal', TemporalFeatureTransformer()),
        ('selector', TreeFeatureSelector()),
        ('scaler', SafeScaler(scaler_type='robust'))
    ])


# ------------------------------------------------------------------------------
# Hyperparameter loader (accepts JSON string or file path)
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
# Temporal split function (avoids pandas datetime issues)
# ------------------------------------------------------------------------------

def temporal_split_by_time(df, time_col, train_ratio=0.7, val_ratio=0.15):
    if time_col not in df.columns:
        raise ValueError(f"Time column '{time_col}' not found")
    df_sorted = df.sort_values(time_col).reset_index(drop=True)
    n = len(df_sorted)
    train_end = int(n * train_ratio)
    val_end = train_end + int(n * val_ratio)
    train_df = df_sorted.iloc[:train_end].copy()
    val_df = df_sorted.iloc[train_end:val_end].copy()
    test_df = df_sorted.iloc[val_end:].copy()
    logger.info("Temporal split (by %s): train=%d, val=%d, test=%d",
                time_col, len(train_df), len(val_df), len(test_df))
    return train_df, val_df, test_df


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
# ONNX export
# ------------------------------------------------------------------------------

def export_to_onnx_direct(model, output_path, input_dim):
    try:
        initial_type = [('float_input', FloatTensorType([None, input_dim]))]
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
    parser = argparse.ArgumentParser(description='Train Random Forest for bank fraud detection')
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

    logger.info("Starting Random Forest training for dataset %s", args.dataset_id)

    original_stdout = sys.stdout
    sys.stdout = sys.stderr

    try:
        hp = load_hyperparameters(args.hyperparameters)

        model_name = hp.get('model_name', 'Random Forest')
        selected_original_features = hp.get('selected_original_features', [])
        all_features = hp.get('all_features', [])

        if not selected_original_features:
            raise ValueError("No original features selected for training")

        logger.info("Model name: %s", model_name)

        dataset_path = Path(args.dataset_path)
        if not dataset_path.exists():
            raise FileNotFoundError(f"Dataset not found: {dataset_path}")

        # Load data
        if dataset_path.suffix.lower() == '.parquet':
            df = pd.read_parquet(dataset_path)
        else:
            df = pd.read_csv(dataset_path)

        # Ensure unique columns
        if not df.columns.is_unique:
            logger.warning("Duplicate column names detected. Keeping first occurrence.")
            df = df.loc[:, ~df.columns.duplicated(keep='first')]

        # Convert TX_Time to datetime
        if 'TX_Time' in df.columns:
            df['TX_Time'] = pd.to_datetime(df['TX_Time'], errors='coerce')
            before = len(df)
            df = df.dropna(subset=['TX_Time']).reset_index(drop=True)
            if len(df) < before:
                logger.warning("Dropped %d rows with invalid TX_Time", before - len(df))

        # Keep available features (including TX_Time for splitting)
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

        # ========== STEP 1: TEMPORAL SPLIT (no leakage) ==========
        if 'TX_Time' not in df_processed.columns:
            raise ValueError("TX_Time column missing")
        train_ratio = 0.7
        val_ratio = 0.15
        df_train, df_val, df_test = temporal_split_by_time(
            df_processed, time_col='TX_Time', train_ratio=train_ratio, val_ratio=val_ratio
        )

        # ========== STEP 2: PER‑SPLIT PREPROCESSING ==========
        logger.info("Applying per-split preprocessing...")
        # ID columns (independent)
        df_train = process_id_columns(df_train)
        df_val = process_id_columns(df_val)
        df_test = process_id_columns(df_test)

        # Customer postal codes: fit region categories on TRAIN only
        customer_region_categories = process_postal_codes_fit(df_train)
        df_train = process_customer_postal_transform(df_train, customer_region_categories)
        df_val = process_customer_postal_transform(df_val, customer_region_categories)
        df_test = process_customer_postal_transform(df_test, customer_region_categories)

        # Merchant postal codes: fit region categories on TRAIN only
        if 'Merchant_Postal_Code' in df_processed.columns:
            merchant_region_categories = process_merchant_postal_fit(df_train)
            df_train = process_merchant_postal_transform(df_train, merchant_region_categories)
            df_val = process_merchant_postal_transform(df_val, merchant_region_categories)
            df_test = process_merchant_postal_transform(df_test, merchant_region_categories)

        # Keep TX_Time for pipeline (do NOT drop it here)
        X_train = df_train.drop('fraud', axis=1)
        X_val = df_val.drop('fraud', axis=1)
        X_test = df_test.drop('fraud', axis=1)
        y_train = df_train['fraud'].values
        y_val = df_val['fraud'].values
        y_test = df_test['fraud'].values

        logger.info("Train: %d rows (fraud rate %.4f)", len(X_train), y_train.mean())
        logger.info("Val:   %d rows (fraud rate %.4f)", len(X_val), y_val.mean())
        logger.info("Test:  %d rows (fraud rate %.4f)", len(X_test), y_test.mean())

        # Identify categorical columns in TRAINING data only (TX_Time excluded by identify_categorical_columns)
        categorical_columns = identify_categorical_columns(X_train)
        logger.info("Categorical columns to encode: %s", categorical_columns)

        # ========== STEP 3: PREPROCESSING PIPELINE (fit on train only) ==========
        preprocessor = create_preprocessing_pipeline(categorical_columns)
        X_train_proc = preprocessor.fit_transform(X_train)
        X_val_proc = preprocessor.transform(X_val)
        X_test_proc = preprocessor.transform(X_test)

        try:
            feature_names = preprocessor.get_feature_names_out()
        except Exception:
            feature_names = [f"feature_{i}" for i in range(X_train_proc.shape[1])]

        logger.info("Processed feature shape: %s", X_train_proc.shape)

        # ========== STEP 4: COST‑SENSITIVE CONFIGURATION ==========
        fraud_weight = hp.get('fraud_weight')
        if fraud_weight is None:
            fraud_ratio = y_train.mean()
            fraud_weight = min(1.0 / fraud_ratio, 50.0) if fraud_ratio > 0 else 30.0
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
        rf_model.fit(X_train_proc, y_train)
        train_time = time.time() - train_start

        # ========== STEP 5: THRESHOLD CALCULATIONS ==========
        y_val_proba = rf_model.predict_proba(X_val_proc)[:, 1]
        max_prob = y_val_proba.max()
        logger.info("Validation max probability: %.4f", max_prob)

        # 5.1 PR‑based (max F1)
        precision_vals, recall_vals, thresholds_pr = precision_recall_curve(y_val, y_val_proba)
        f1_scores = 2 * precision_vals[:-1] * recall_vals[:-1] / (precision_vals[:-1] + recall_vals[:-1] + 1e-9)
        best_idx = np.argmax(f1_scores)
        pr_threshold = thresholds_pr[best_idx]
        best_f1 = f1_scores[best_idx]
        logger.info("PR‑based threshold (max F1): %.4f (F1=%.4f)", pr_threshold, best_f1)

        # 5.2 F‑beta (β=2.0)
        fbeta_threshold, fbeta_score_val = find_threshold_by_fbeta(y_val, y_val_proba, beta=2.0)
        logger.info("F-beta (β=2.0): threshold=%.4f, score=%.4f", fbeta_threshold, fbeta_score_val)

        # 5.3 Cost‑based (FP=1, FN=10)
        cost_threshold, min_cost = find_threshold_by_cost(y_val, y_val_proba, cost_fp=1.0, cost_fn=10.0)
        logger.info("Cost-based: threshold=%.4f, min_cost=%.0f", cost_threshold, min_cost)

        # 5.4 User threshold from UI
        user_threshold = hp.get('detection_threshold')
        if user_threshold is not None:
            final_threshold = float(user_threshold)
            logger.info("Using user‑supplied threshold from UI: %.4f", final_threshold)
            if final_threshold > max_prob:
                logger.warning("User threshold %.4f > max prob %.4f → will detect nothing.", final_threshold, max_prob)
        else:
            final_threshold = pr_threshold
            logger.info("No UI threshold, using PR‑based: %.4f", final_threshold)

        # ========== STEP 6: TEST EVALUATION ==========
        y_test_proba = rf_model.predict_proba(X_test_proc)[:, 1]
        y_test_pred = (y_test_proba >= final_threshold).astype(int)

        accuracy = accuracy_score(y_test, y_test_pred)
        precision = precision_score(y_test, y_test_pred, zero_division=0)
        recall = recall_score(y_test, y_test_pred, zero_division=0)
        f1 = f1_score(y_test, y_test_pred, zero_division=0)
        auc_roc = roc_auc_score(y_test, y_test_proba)
        pr_auc = average_precision_score(y_test, y_test_proba)
        cm = confusion_matrix(y_test, y_test_pred)

        logger.info("Test metrics with threshold %.4f:", final_threshold)
        logger.info("  Accuracy: %.4f, Precision: %.4f, Recall: %.4f, F1: %.4f, AUC-ROC: %.4f, PR-AUC: %.4f",
                    accuracy, precision, recall, f1, auc_roc, pr_auc)

        # Feature importance
        importances = rf_model.feature_importances_
        feature_importance_map = dict(sorted(zip(feature_names, importances), key=lambda x: x[1], reverse=True))

        # ========== STEP 7: SAVE ARTIFACTS (atomic) ==========
        timestamp = args.timestamp
        output_models_dir = Path("/app/output/models")
        output_onnx_dir = Path("/app/output/onnx")
        output_pipelines_dir = Path("/app/output/pipelines")
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

        # Export ONNX
        onnx_file = output_onnx_dir / f"{base_filename}.onnx"
        onnx_path = export_to_onnx_direct(rf_model, onnx_file, X_train_proc.shape[1])
        if not onnx_path:
            raise RuntimeError("ONNX export failed")

        ended_at = datetime.now()
        total_duration = time.time() - overall_start

        # Build result dictionary with JSON‑safe conversion
        result_raw = {
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
                'accuracy': accuracy,
                'precision': precision,
                'recall': recall,
                'f1_score': f1,
                'auc_roc': auc_roc,
                'pr_auc': pr_auc,
                'avg_fraud_score': float(y_test_proba.mean()),
            },
            'confusion_matrix': {
                'true_negatives': int(cm[0, 0]),
                'false_positives': int(cm[0, 1]),
                'false_negatives': int(cm[1, 0]),
                'true_positives': int(cm[1, 1]),
            },
            'thresholds': {
                'pr_optimal': pr_threshold,
                'pr_f1': best_f1,
                'fbeta': fbeta_threshold,
                'fbeta_score': fbeta_score_val,
                'cost': cost_threshold,
                'min_cost': min_cost,
                'final': final_threshold,
            },
            'training_stats': {
                'train_rows': int(len(X_train)),
                'val_rows': int(len(X_val)),
                'test_rows': int(len(X_test)),
                'fraud_rate_test': float(y_test.mean()),
                'train_time_seconds': train_time,
            },
            'start_time': started_at.isoformat(),
            'end_time': ended_at.isoformat(),
            'duration_seconds': total_duration,
        }

        # Convert numpy types to Python native
        result = make_json_safe(result_raw)

        sys.stdout = original_stdout
        print(json.dumps(result))

    except Exception as e:
        logger.exception("Random Forest training failed")
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