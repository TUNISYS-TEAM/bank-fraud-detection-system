#!/usr/bin/env python3
"""
Train a Random Forest model with fixed hyperparameters.
Shows only original features in UI but automatically includes ALL client behavior features.
Extracts numeric parts from ID columns using substr approach.
Only encodes raw categorical columns (Merchant_Id, category, Channel).
TX_Time is handled by TemporalFeatureTransformer and should NOT be encoded.
"""

import argparse
import json
import logging
import sys
import time
import os
import re
from pathlib import Path
from datetime import datetime

import numpy as np
import pandas as pd
import joblib
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score, f1_score, roc_auc_score, confusion_matrix
)
from sklearn.pipeline import Pipeline
import onnx
import skl2onnx
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

# Add the src path to sys.path to import your modules
sys.path.insert(0, '/app/src')
from ml_pipeline.inference.thresholding import (
    find_threshold_by_fbeta,
    find_threshold_by_cost,
    find_threshold_for_recall
)
from ml_pipeline.pipeline.data_split import temporal_split
from ml_pipeline.transformers.behavioral.temporal_features import TemporalFeatureTransformer
from ml_pipeline.transformers.encoders.categorical_encoder import SafeOneHotEncoder
from ml_pipeline.transformers.encoders.feature_selectors import TreeFeatureSelector
from ml_pipeline.transformers.scalers.monetary_scaler import SafeScaler
from ml_pipeline.utils.postal_codes import PostalCodeResolver

# Configure logging to stderr
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
    stream=sys.stderr
)
logger = logging.getLogger(__name__)


def extract_numeric_from_id(value):
    """
    Extract numeric part from ID strings using substr approach:
    'DEV-710802' -> 710802
    'ACC-54586446' -> 54586446
    """
    if pd.isna(value):
        return 0
    try:
        # If it's already numeric, return as float
        if isinstance(value, (int, float)):
            return float(value)
        # Convert to string and extract numbers
        s = str(value)
        # Find where the number starts after the hyphen
        if '-' in s:
            parts = s.split('-')
            if len(parts) > 1 and parts[1].replace('.', '', 1).isdigit():
                return float(parts[1])
        # Fallback to regex for other patterns
        numbers = re.findall(r'\d+', s)
        if numbers:
            return float(numbers[0])
        return 0
    except:
        return 0


def process_id_columns(df):
    """
    Process ID columns (Account_Id, Device_Id) to extract numeric parts using substr.
    """
    df_processed = df.copy()
    
    # List of ID columns to process
    id_columns = ['Account_Id', 'Device_Id']
    
    for col in id_columns:
        if col in df_processed.columns:
            logger.info(f"Processing ID column with substr: {col}")
            df_processed[col] = df_processed[col].apply(extract_numeric_from_id).astype(np.float32)
    
    return df_processed


def process_postal_codes(df):
    """
    Process postal code columns using PostalCodeResolver.
    Converts postal codes to region codes (numerical).
    """
    resolver = PostalCodeResolver()
    
    # Process Customer_Postal_Code if present
    if 'Customer_Postal_Code' in df.columns:
        logger.info("Processing Customer_Postal_Code...")
        df = resolver.enrich_dataframe(df, 'Customer_Postal_Code', add_gov=False, add_deleg=False, add_region=True)
        # Convert region to numerical code
        df['Customer_Region'] = pd.Categorical(df['region']).codes.astype(np.float32)
        df = df.drop(['Customer_Postal_Code', 'region'], axis=1)
    
    # Process Merchant_Postal_Code if present
    if 'Merchant_Postal_Code' in df.columns:
        logger.info("Processing Merchant_Postal_Code...")
        df = resolver.enrich_dataframe(df, 'Merchant_Postal_Code', add_gov=False, add_deleg=False, add_region=True)
        # Convert region to numerical code
        df['Merchant_Region'] = pd.Categorical(df['region']).codes.astype(np.float32)
        df = df.drop(['Merchant_Postal_Code', 'region'], axis=1)
    
    return df


def encode_categorical_columns(df):
    """
    Convert ONLY raw categorical columns (Merchant_Id, category, Channel) to numerical codes.
    TX_Time is handled by TemporalFeatureTransformer and should NOT be encoded.
    Client behavior columns (starting with 'client_') are already numerical.
    """
    df_encoded = df.copy()
    
    # List of columns that should NOT be encoded:
    # - TX_Time (handled by TemporalFeatureTransformer)
    # - client_* features (already numerical)
    # - ID columns (already processed numerically)
    # - Region columns (already numerical)
    exclude_columns = [
        'TX_Time',  # CRITICAL: Do not encode TX_Time
        'Account_Id', 
        'Device_Id', 
        'Customer_Region', 
        'Merchant_Region'
    ]
    
    # Also exclude any column starting with 'client_'
    exclude_patterns = ['client_']
    
    for col in df_encoded.columns:
        # Skip if column is in exclude list
        if col in exclude_columns:
            continue
            
        # Skip if column matches exclude patterns
        should_skip = any(col.startswith(pattern) for pattern in exclude_patterns)
        if should_skip:
            continue
        
        # Only encode object/category columns (raw categorical data)
        if df_encoded[col].dtype == 'object' or df_encoded[col].dtype.name == 'category':
            logger.info(f"Converting categorical column to codes: {col}")
            df_encoded[col] = pd.Categorical(df_encoded[col]).codes.astype(np.float32)
            # Replace -1 (for NaN) with 0
            df_encoded[col] = df_encoded[col].replace(-1, 0)
    
    return df_encoded


def create_preprocessing_pipeline():
    """
    Create the preprocessing pipeline using existing transformers.
    Note: SafeOneHotEncoder will only encode the columns we want (client_usual_*)
    """
    return Pipeline([
        ('temporal', TemporalFeatureTransformer()),
        ('onehot', SafeOneHotEncoder()),  # This will encode client_usual_* columns
        ('selector', TreeFeatureSelector()),
        ('scaler', SafeScaler(scaler_type='robust'))
    ])


def export_to_onnx_direct(model, output_path, input_dim):
    """
    Directly export a sklearn model to ONNX.
    """
    try:
        initial_type = [('float_input', FloatTensorType([None, input_dim]))]
        onnx_model = convert_sklearn(model, initial_types=initial_type)
        onnx.save_model(onnx_model, str(output_path))
        
        logger.info(f"ONNX model exported successfully to: {output_path}")
        return str(output_path)
    except Exception as e:
        logger.error(f"ONNX export failed: {e}")
        return None


def main():
    parser = argparse.ArgumentParser(description='Train Random Forest model and export to ONNX')
    parser.add_argument('--dataset-path', required=True, help='Path to dataset with precomputed features (Parquet/CSV)')
    parser.add_argument('--dataset-id', required=True, help='Dataset UUID')
    parser.add_argument('--job-id', required=True, help='Job UUID')
    parser.add_argument('--hyperparameters', required=True, help='Path to hyperparameters JSON')
    parser.add_argument('--test-size', type=float, default=0.2, help='Test size ratio')
    parser.add_argument('--random-seed', type=int, default=42, help='Random seed')
    args = parser.parse_args()

    logger.info(f"Starting Random Forest training for dataset {args.dataset_id}")

    # Redirect stdout to stderr for logs, keep a clean stdout for JSON
    original_stdout = sys.stdout
    sys.stdout = sys.stderr
    
    try:
        # 1. Load hyperparameters
        with open(args.hyperparameters, 'r') as f:
            hp = json.load(f)

        # Get model name from hyperparameters (sent from frontend)
        model_name = hp.get('model_name', 'Random Forest')

        # 2. Get selected original features and all features for training
        selected_original_features = hp.get('selected_original_features', [])
        all_features = hp.get('all_features', [])
        
        if not selected_original_features:
            raise ValueError("No original features selected for training")
        
        if not all_features:
            raise ValueError("No features available for training")
        
        logger.info(f"Model name: {model_name}")
        logger.info(f"Selected original features ({len(selected_original_features)}): {selected_original_features}")
        logger.info(f"Total features for training ({len(all_features)}): {all_features}")

        # 3. Load dataset
        logger.info(f"Loading dataset from {args.dataset_path}")
        if args.dataset_path.endswith('.parquet'):
            df = pd.read_parquet(args.dataset_path)
        else:
            df = pd.read_csv(args.dataset_path)
        logger.info(f"Loaded {len(df)} rows, columns: {list(df.columns)}")

        # 4. Process ID columns first (Account_Id, Device_Id) using substr
        logger.info("Processing ID columns with substr...")
        df = process_id_columns(df)

        # 5. Process postal codes
        logger.info("Processing postal codes...")
        df = process_postal_codes(df)

        # 6. Encode ONLY raw categorical columns (Merchant_Id, category, Channel)
        #    IMPORTANT: TX_Time is explicitly excluded from encoding
        logger.info("Encoding raw categorical columns (excluding TX_Time)...")
        df = encode_categorical_columns(df)

        # 7. Separate features and target
        if 'fraud' not in df.columns:
            raise ValueError("Dataset must contain a 'fraud' column as target")
        
        # Filter to include ALL features for training
        available_features = [f for f in all_features if f in df.columns]
        # Add the new region columns if they were created
        if 'Customer_Region' in df.columns and 'Customer_Region' not in available_features:
            available_features.append('Customer_Region')
        if 'Merchant_Region' in df.columns and 'Merchant_Region' not in available_features:
            available_features.append('Merchant_Region')
        
        if not available_features:
            raise ValueError("None of the required features are available in the dataset")
        
        X = df[available_features].copy()
        y = df['fraud'].values

        # 8. Add target back temporarily for split
        df_processed = X.copy()
        df_processed['fraud'] = y

        # 9. Perform temporal split
        logger.info("Performing temporal split...")
        df_train, df_val, df_test = temporal_split(
            df_processed,
            train_ratio=0.7,
            val_ratio=0.15,
            time_col='TX_Time'
        )

        # 10. Separate features and target again
        X_train = df_train.drop('fraud', axis=1)
        X_val = df_val.drop('fraud', axis=1)
        X_test = df_test.drop('fraud', axis=1)
        
        y_train = df_train['fraud'].values
        y_val = df_val['fraud'].values
        y_test = df_test['fraud'].values

        logger.info(f"Train: {len(X_train)} rows, Val: {len(X_val)} rows, Test: {len(X_test)} rows")
        logger.info(f"Fraud rate — train: {y_train.mean():.4f}, val: {y_val.mean():.4f}, test: {y_test.mean():.4f}")

        # 11. Create and fit preprocessing pipeline
        logger.info("Creating and fitting preprocessing pipeline...")
        preprocessor = create_preprocessing_pipeline()
        
        # Fit on training data only
        X_train_processed = preprocessor.fit_transform(X_train)
        X_val_processed = preprocessor.transform(X_val)
        X_test_processed = preprocessor.transform(X_test)
        
        # Get feature names after processing
        try:
            feature_names = preprocessor.get_feature_names_out()
        except:
            feature_names = [f"feature_{i}" for i in range(X_train_processed.shape[1])]

        logger.info(f"Processed feature shape: {X_train_processed.shape}")

        # 12. Fixed hyperparameters
        fixed_params = {
            'min_samples_split': 20,
            'min_samples_leaf': 10,
            'class_weight': 'balanced',
            'random_state': args.random_seed,
            'n_jobs': -1,
            'verbose': 0,
            'max_features': 'sqrt',
            'bootstrap': True,
        }

        # User parameters from UI
        user_params = {
            'n_estimators': hp.get('n_estimators', 100),
            'max_depth': hp.get('max_depth', 15),
        }

        rf_params = {**fixed_params, **user_params}
        logger.info(f"Training Random Forest with params: {rf_params}")

        # 13. Train Random Forest
        start_time = time.time()
        rf_model = RandomForestClassifier(**rf_params)
        rf_model.fit(X_train_processed, y_train)
        train_time = time.time() - start_time

        # 14. Get predictions on validation set for threshold tuning
        y_val_proba = rf_model.predict_proba(X_val_processed)[:, 1]

        # 15. Find optimal thresholds on validation set
        logger.info("Finding optimal thresholds on validation set:")
        
        fbeta_threshold, fbeta_score_val = find_threshold_by_fbeta(y_val, y_val_proba, beta=2.0)
        logger.info(f"  F-beta (β=2.0): threshold={fbeta_threshold:.4f}, score={fbeta_score_val:.4f}")
        
        cost_threshold, min_cost = find_threshold_by_cost(y_val, y_val_proba, cost_fp=1.0, cost_fn=10.0)
        logger.info(f"  Cost-based: threshold={cost_threshold:.4f}, min_cost={min_cost:.0f}")
        
        # 16. Use user threshold if provided
        user_threshold = hp.get('detection_threshold')
        if user_threshold is not None:
            final_threshold = float(user_threshold)
            logger.info(f"Using user‑supplied threshold: {final_threshold:.4f}")
        else:
            final_threshold = fbeta_threshold
            logger.info(f"No user threshold provided, using F-beta threshold: {final_threshold:.4f}")

        # 17. Evaluate on test set
        y_test_proba = rf_model.predict_proba(X_test_processed)[:, 1]
        y_test_pred = (y_test_proba > final_threshold).astype(int)

        accuracy = accuracy_score(y_test, y_test_pred)
        precision = precision_score(y_test, y_test_pred, zero_division=0)
        recall = recall_score(y_test, y_test_pred, zero_division=0)
        f1 = f1_score(y_test, y_test_pred, zero_division=0)
        auc_roc = roc_auc_score(y_test, y_test_proba)
        cm = confusion_matrix(y_test, y_test_pred)

        logger.info(f"Test metrics with threshold {final_threshold:.4f}:")
        logger.info(f"  Accuracy: {accuracy:.4f}")
        logger.info(f"  Precision: {precision:.4f}")
        logger.info(f"  Recall: {recall:.4f}")
        logger.info(f"  F1: {f1:.4f}")
        logger.info(f"  AUC-ROC: {auc_roc:.4f}")

        # 18. Create full pipeline (preprocessor + model)
        full_pipeline = Pipeline([
            ('preprocessor', preprocessor),
            ('classifier', rf_model)
        ])

        # 19. Save the full pipeline
        model_filename = f"random_forest_{args.job_id}.joblib"
        model_path = f"/app/output/models/{model_filename}"
        Path("/app/output/models").mkdir(parents=True, exist_ok=True)
        joblib.dump(full_pipeline, model_path)
        
        # 20. Export to ONNX
        logger.info("Exporting model to ONNX format...")
        
        onnx_dir = Path("/app/output/onnx")
        onnx_dir.mkdir(parents=True, exist_ok=True)
        
        onnx_filename = f"random_forest_{args.job_id}.onnx"
        onnx_file = onnx_dir / onnx_filename
        
        input_dim = X_train_processed.shape[1]
        
        onnx_path = export_to_onnx_direct(
            model=rf_model,
            output_path=onnx_file,
            input_dim=input_dim
        )
        
        if onnx_path:
            logger.info(f"ONNX model exported successfully to: {onnx_path}")
            
            # Restore stdout and output JSON
            sys.stdout = original_stdout
            
            # JSON response with all fields required by Java service
            result = {
                'status': 'COMPLETED',
                'job_id': args.job_id,
                'dataset_id': args.dataset_id,
                'model_name': model_name,
                'onnx_path': onnx_path,
                'model_path': model_path,
                'selected_original_features': selected_original_features,
                'hyperparameters_used': rf_params,
                'metrics': {
                    'accuracy': accuracy,
                    'precision': precision,
                    'recall': recall,
                    'f1_score': f1,
                    'auc_roc': auc_roc,
                },
                'confusion_matrix': {
                    'true_negatives': int(cm[0, 0]),
                    'false_positives': int(cm[0, 1]),
                    'false_negatives': int(cm[1, 0]),
                    'true_positives': int(cm[1, 1]),
                },
                'thresholds': {
                    'fbeta': float(fbeta_threshold),
                    'fbeta_score': float(fbeta_score_val),
                    'cost': float(cost_threshold),
                    'min_cost': float(min_cost),
                    'final': float(final_threshold),
                },
                'training_stats': {
                    'train_rows': len(X_train),
                    'val_rows': len(X_val),
                    'test_rows': len(X_test),
                    'fraud_rate_test': float(y_test.mean()),
                },
                'start_time': datetime.now().isoformat(),
                'end_time': datetime.now().isoformat(),
                'duration_seconds': train_time,
            }
            print(json.dumps(result))
        else:
            raise Exception("ONNX export failed")

    except Exception as e:
        logger.exception("Training failed")
        sys.stdout = original_stdout
        error_result = {
            'status': 'FAILED',
            'error': str(e)
        }
        print(json.dumps(error_result))
        sys.exit(1)


if __name__ == '__main__':
    main()