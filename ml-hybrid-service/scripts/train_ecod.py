"""
Train an ECOD (Empirical Cumulative Distribution-based Outlier Detection) model.
Unsupervised anomaly detection – with dynamic thresholding and cost-sensitive evaluation.
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
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score, f1_score,
    roc_auc_score, confusion_matrix, precision_recall_curve, average_precision_score
)
from sklearn.compose import ColumnTransformer
from sklearn.preprocessing import RobustScaler
from sklearn.pipeline import Pipeline

sys.path.insert(0, '/app/src')

from app.models.unsupervised.anomaly_models import ECODFraudModel
from shared_ml.evaluation.thresholding import (
    find_threshold_by_fbeta,
    find_threshold_by_cost,
)
from shared_ml.utils.postal_codes import PostalCodeResolver

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


def process_customer_postal_fit(df_train):
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


def encode_categorical_columns_fit(df_train, categorical_cols):
    """Fit label encoders on training data only (vectorized)."""
    encoders = {}
    df_train_enc = df_train.copy()
    for col in categorical_cols:
        if col not in df_train.columns:
            continue
        unique_vals = df_train[col].astype(str).unique()
        mapping = {val: idx for idx, val in enumerate(unique_vals)}
        encoders[col] = mapping
        df_train_enc[col] = df_train[col].astype(str).map(mapping).astype(np.float32)
    logger.info("Categorical encoders fitted for %d columns", len(encoders))
    return df_train_enc, encoders


def encode_categorical_columns_transform(df, encoders):
    """Transform using pre‑fitted mapping (unknown → -1)."""
    df_enc = df.copy()
    for col, mapping in encoders.items():
        if col not in df.columns:
            continue
        df_enc[col] = df_enc[col].astype(str).map(lambda x: mapping.get(x, -1)).astype(np.float32)
    return df_enc


def create_preprocessing_pipeline(numeric_cols):
    """Only scaling – no categorical encoding (already done)."""
    scaler = RobustScaler()
    preprocessor = ColumnTransformer(
        transformers=[('num', scaler, numeric_cols)],
        remainder='passthrough'
    )
    return Pipeline([('scaler', preprocessor)])


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
# Main
# ------------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description='Train ECOD for bank fraud detection')
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

    logger.info("Starting ECOD training for dataset %s", args.dataset_id)

    original_stdout = sys.stdout
    sys.stdout = sys.stderr

    try:
        hp = load_hyperparameters(args.hyperparameters)

        model_name = hp.get('model_name', 'ECOD')
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

        # Convert TX_Time to datetime, drop invalid rows
        if 'TX_Time' in df.columns:
            df['TX_Time'] = pd.to_datetime(df['TX_Time'], errors='coerce')
            before = len(df)
            df = df.dropna(subset=['TX_Time']).reset_index(drop=True)
            if len(df) < before:
                logger.warning("Dropped %d rows with invalid TX_Time", before - len(df))

        # Keep only available features (including TX_Time for splitting)
        available_features = [f for f in all_features if f in df.columns]
        if not available_features:
            raise ValueError("None of the required features are available in the dataset")

        # Prepare data for split (keep TX_Time)
        feature_cols_for_split = available_features.copy()
        if 'TX_Time' not in feature_cols_for_split and 'TX_Time' in df.columns:
            feature_cols_for_split.append('TX_Time')
        
        X = df[feature_cols_for_split].copy()
        y = df['fraud'].values
        df_processed = X.copy()
        df_processed['fraud'] = y

        # Identify categorical columns (excluding TX_Time and IDs)
        categorical_cols = []
        for col in df_processed.columns:
            if col not in ['TX_Time', 'fraud', 'Account_Id', 'Device_Id']:
                if df_processed[col].dtype == 'object' or df_processed[col].dtype.name == 'category':
                    categorical_cols.append(col)

        # ========== STEP 1: TEMPORAL SPLIT (no leakage) ==========
        logger.info("Performing temporal split...")
        if 'TX_Time' not in df_processed.columns:
            raise ValueError("TX_Time column missing")
        
        train_ratio = 0.7
        val_ratio = 0.15
        df_train, df_val, df_test = temporal_split_by_time(
            df_processed, time_col='TX_Time', train_ratio=train_ratio, val_ratio=val_ratio
        )

        # ========== STEP 2: PER‑SPLIT PREPROCESSING (fit on train only) ==========
        logger.info("Applying per-split preprocessing...")
        
        # 2.1 ID columns (independent per split)
        df_train = process_id_columns(df_train)
        df_val = process_id_columns(df_val)
        df_test = process_id_columns(df_test)
        
        # 2.2 Customer postal codes: fit region categories on TRAIN only
        customer_region_categories = process_customer_postal_fit(df_train)
        df_train = process_customer_postal_transform(df_train, customer_region_categories)
        df_val = process_customer_postal_transform(df_val, customer_region_categories)
        df_test = process_customer_postal_transform(df_test, customer_region_categories)

        # 2.3 Merchant postal codes (if present)
        if 'Merchant_Postal_Code' in df_processed.columns:
            merchant_region_categories = process_merchant_postal_fit(df_train)
            df_train = process_merchant_postal_transform(df_train, merchant_region_categories)
            df_val = process_merchant_postal_transform(df_val, merchant_region_categories)
            df_test = process_merchant_postal_transform(df_test, merchant_region_categories)
        
        # 2.4 Categorical encoding: fit LabelEncoders on TRAIN only
        df_train, encoders = encode_categorical_columns_fit(df_train, categorical_cols)
        df_val = encode_categorical_columns_transform(df_val, encoders)
        df_test = encode_categorical_columns_transform(df_test, encoders)

        # Drop TX_Time from features (not needed for ECOD)
        X_train = df_train.drop(['fraud', 'TX_Time'], axis=1)
        X_val = df_val.drop(['fraud', 'TX_Time'], axis=1)
        X_test = df_test.drop(['fraud', 'TX_Time'], axis=1)
        y_train = df_train['fraud'].values
        y_val = df_val['fraud'].values
        y_test = df_test['fraud'].values

        # Ensure all columns are numeric (should be after encoding)
        for X_set in [X_train, X_val, X_test]:
            for col in X_set.columns:
                if X_set[col].dtype == 'object':
                    logger.warning("Column %s is still object – converting to numeric", col)
                    X_set[col] = pd.to_numeric(X_set[col], errors='coerce').fillna(0)

        logger.info("Train: %d rows (fraud rate %.4f)", len(X_train), y_train.mean())
        logger.info("Val:   %d rows (fraud rate %.4f)", len(X_val), y_val.mean())
        logger.info("Test:  %d rows (fraud rate %.4f)", len(X_test), y_test.mean())

        # ========== STEP 3: SCALING (fit on train only) ==========
        logger.info("Creating and fitting preprocessing pipeline...")
        numeric_cols = X_train.select_dtypes(include=[np.number]).columns.tolist()
        preprocessor = create_preprocessing_pipeline(numeric_cols)
        
        X_train_processed = preprocessor.fit_transform(X_train)
        X_val_processed = preprocessor.transform(X_val)
        X_test_processed = preprocessor.transform(X_test)

        # ========== STEP 4: ECOD MODEL ==========
        contamination = hp.get('contamination', 0.05)
        threshold_metric = hp.get('threshold_metric', 'fbeta')
        beta = hp.get('beta', 2.0)

        model = ECODFraudModel(
            contamination=contamination,
            threshold_metric=threshold_metric,
            beta=beta
        )

        logger.info("Fitting ECOD...")
        train_start = time.time()
        model.fit(X_train_processed, y_train, X_val_processed, y_val)
        train_time = time.time() - train_start

        # ========== STEP 5: MULTIPLE THRESHOLD CALCULATIONS ==========
        y_val_proba = model.predict_proba(X_val_processed)
        
        # 5.1 PR-based (max F1) threshold
        precision_vals, recall_vals, thresholds_pr = precision_recall_curve(y_val, y_val_proba)
        f1_scores = 2 * precision_vals[:-1] * recall_vals[:-1] / (precision_vals[:-1] + recall_vals[:-1] + 1e-9)
        best_idx = np.argmax(f1_scores)
        pr_threshold = thresholds_pr[best_idx]
        best_f1 = f1_scores[best_idx]
        logger.info("PR‑based threshold (max F1): %.4f (F1=%.4f)", pr_threshold, best_f1)

        # 5.2 F-beta (β=2.0) threshold
        fbeta_threshold, fbeta_score_val = find_threshold_by_fbeta(y_val, y_val_proba, beta=2.0)
        logger.info("F-beta (β=2.0): threshold=%.4f, score=%.4f", fbeta_threshold, fbeta_score_val)

        # 5.3 Cost-based threshold (FP cost=1, FN cost=10)
        cost_threshold, min_cost = find_threshold_by_cost(y_val, y_val_proba, cost_fp=1.0, cost_fn=10.0)
        logger.info("Cost-based: threshold=%.4f, min_cost=%.0f", cost_threshold, min_cost)

        # Auto threshold from model (F-beta on validation)
        auto_threshold = model.threshold_

        # 5.4 User threshold from UI (overrides all if provided)
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
            final_threshold = pr_threshold  # fallback to PR-optimal
            logger.info("No UI threshold provided, using PR‑based threshold: %.4f", final_threshold)

        # ========== STEP 6: TEST EVALUATION ==========
        y_test_proba = model.predict_proba(X_test_processed)
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

        # ========== STEP 7: SAVE MODEL AND PIPELINE (atomic) ==========
        timestamp = args.timestamp
        output_models_dir = Path("/app/output/models")
        output_pipelines_dir = Path("/app/output/pipelines")
        output_models_dir.mkdir(parents=True, exist_ok=True)
        output_pipelines_dir.mkdir(parents=True, exist_ok=True)

        base_filename = f"trained_model_{timestamp}"
        model_path = output_models_dir / f"{base_filename}.joblib"
        pipeline_path = output_pipelines_dir / f"pipeline_{timestamp}.joblib"

        # Write model to a temporary file, then rename (atomic)
        temp_model_path = output_models_dir / f"{base_filename}.tmp"
        try:
            joblib.dump(model, temp_model_path)
            # Force flush to disk
            with open(temp_model_path, 'rb') as f:
                pass
            # Rename to final name
            temp_model_path.rename(model_path)
            logger.info("Model dumped to %s", model_path)
            if not model_path.exists():
                raise RuntimeError(f"Model file missing after rename: {model_path}")
            logger.info("Model file size: %d bytes", model_path.stat().st_size)
        except Exception as e:
            logger.error("Failed to save model: %s", e)
            raise
        finally:
            if temp_model_path.exists():
                temp_model_path.unlink()

        # Write pipeline directly (small file, no rename needed)
        joblib.dump(preprocessor, pipeline_path)
        logger.info("Pipeline dumped to %s", pipeline_path)

        ended_at = datetime.now()
        total_duration = time.time() - overall_start

        # Restore stdout and print result
        sys.stdout = original_stdout
        result = {
            'status': 'COMPLETED',
            'job_id': args.job_id,
            'dataset_id': args.dataset_id,
            'model_name': model_name,
            'model_path': str(model_path),
            'pipeline_path': str(pipeline_path),
            'selected_original_features': selected_original_features,
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
        logger.exception("ECOD Training failed")
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