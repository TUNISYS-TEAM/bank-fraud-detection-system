#!/usr/bin/env python3
"""
Train a Random Forest model using the full fraud detection pipeline.
"""

import argparse
import json
import logging
import sys
import time
from pathlib import Path
from datetime import datetime

import numpy as np
import pandas as pd
import joblib

# Add project root to path so we can import ml_pipeline
sys.path.insert(0, '/app/src')
from ml_pipeline.pipeline.fraud_pipeline import FraudFeaturePipeline, create_tree_pipeline
from ml_pipeline.pipeline.data_split import temporal_split, save_split_report
from ml_pipeline.models.supervised.tree_models import RandomForestFraudModel

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
    stream=sys.stderr
)
logger = logging.getLogger(__name__)


def main():
    parser = argparse.ArgumentParser(description='Train Random Forest model with full pipeline')
    parser.add_argument('--dataset-path', required=True, help='Path to raw dataset (Parquet/CSV)')
    parser.add_argument('--dataset-id', required=True, help='Dataset UUID')
    parser.add_argument('--job-id', required=True, help='Job UUID')
    parser.add_argument('--hyperparameters', required=True, help='Path to hyperparameters JSON')
    parser.add_argument('--test-size', type=float, default=0.2, help='Test size ratio')
    parser.add_argument('--random-seed', type=int, default=42, help='Random seed')
    args = parser.parse_args()

    logger.info(f"Starting Random Forest training for dataset {args.dataset_id}")

    try:
        # 1. Load hyperparameters
        with open(args.hyperparameters, 'r') as f:
            hp = json.load(f)

        # 2. Load raw dataset
        logger.info(f"Loading dataset from {args.dataset_path}")
        if args.dataset_path.endswith('.parquet'):
            df = pd.read_parquet(args.dataset_path)
        else:
            df = pd.read_csv(args.dataset_path)
        logger.info(f"Loaded {len(df)} rows, columns: {list(df.columns)}")

        # 3. Temporal split (train/val/test)
        logger.info("Performing temporal split (train/val/test)")
        df_train, df_val, df_test = temporal_split(
            df,
            train_ratio=0.70,
            val_ratio=0.15,
            time_col="TX_Time"
        )
        logger.info(f"Train: {len(df_train)} rows, Val: {len(df_val)}, Test: {len(df_test)}")

        # 4. Build and fit the feature pipeline
        logger.info("Creating feature pipeline")
        pipeline = create_tree_pipeline()  # includes behavior, temporal, onehot, selector
        feature_pipeline = FraudFeaturePipeline(pipeline, name="random_forest")
        
        # FIT THE PIPELINE - this already transforms the training data internally
        logger.info("Fitting pipeline on training data...")
        feature_pipeline.fit(df_train)

        # 5. Get transformed data - use the cached version for training!
        # IMPORTANT: The fit() method already transformed the training data internally
        # We need to access that cached data instead of transforming again
        
        # First, let's check if the pipeline has a method to get cached data
        if hasattr(feature_pipeline, '_cached_X_train') and feature_pipeline._cached_X_train is not None:
            # If the pipeline has been modified to cache data, use it
            X_train = feature_pipeline._cached_X_train
            logger.info("Using cached training features from fit()")
        else:
            # Fallback: transform again (but this is slower)
            logger.warning("No cached training data found, transforming again (this will be slower)")
            X_train = feature_pipeline.transform_train(df_train)
        
        # Transform validation and test sets (these must be done separately)
        logger.info("Transforming validation set...")
        X_val = feature_pipeline.transform(df_val)
        
        logger.info("Transforming test set...")
        X_test = feature_pipeline.transform(df_test)

        y_train = df_train['fraud'].values
        y_val   = df_val['fraud'].values
        y_test  = df_test['fraud'].values

        logger.info(f"Features shape: train {X_train.shape}, val {X_val.shape}, test {X_test.shape}")

        # 6. Train Random Forest using the wrapper
        logger.info("Training Random Forest model...")
        model = RandomForestFraudModel(
            n_estimators=hp.get('n_estimators', 100),
            max_depth=hp.get('max_depth', 10),
            min_samples_leaf=hp.get('min_samples_leaf', 10),
            threshold_metric='fbeta',
            beta=2.0
        )
        # Override sklearn's min_samples_split if provided
        if 'min_samples_split' in hp:
            model.min_samples_split = hp['min_samples_split']

        start_time = time.time()
        model.fit(X_train, y_train, X_val, y_val)
        train_time = time.time() - start_time

        # 7. Evaluate on test set
        metrics = model.evaluate(y_test, X_test, split_name="TEST")

        # 8. Prepare output JSON
        result = {
            'status': 'COMPLETED',
            'job_id': args.job_id,
            'dataset_id': args.dataset_id,
            'hyperparameters_used': {
                'n_estimators': model.n_estimators,
                'max_depth': model.max_depth,
                'min_samples_split': getattr(model, 'min_samples_split', 2),
                'min_samples_leaf': model.min_samples_leaf,
                'random_seed': args.random_seed
            },
            'metrics': {
                'f1_score': metrics['f1'],
                'precision': metrics['precision'],
                'recall': metrics['recall'],
                'auc_roc': metrics['roc_auc'],
            },
            'confusion_matrix': {
                'true_negatives': metrics.get('confusion_matrix', {}).get('true_negatives', 0),
                'false_positives': metrics.get('confusion_matrix', {}).get('false_positives', 0),
                'false_negatives': metrics.get('confusion_matrix', {}).get('false_negatives', 0),
                'true_positives': metrics.get('confusion_matrix', {}).get('true_positives', 0)
            },
            'feature_importance': model.get_feature_importance(),
            'training_stats': {
                'train_rows': X_train.shape[0],
                'val_rows': X_val.shape[0],
                'test_rows': X_test.shape[0],
                'fraud_rate_test': float(np.mean(y_test)),
                'best_threshold': float(model.threshold_)
            },
            'start_time': datetime.now().isoformat(),
            'end_time': datetime.now().isoformat(),
            'duration_seconds': train_time
        }

        # 9. Save model and pipeline
        model_filename = f"random_forest_{args.job_id}.joblib"
        model_path = f"/app/output/models/{model_filename}"
        Path("/app/output/models").mkdir(parents=True, exist_ok=True)
        model.save(model_path)

        pipeline_filename = f"pipeline_{args.job_id}.joblib"
        pipeline_path = f"/app/output/pipelines/{pipeline_filename}"
        Path("/app/output/pipelines").mkdir(parents=True, exist_ok=True)
        feature_pipeline.save(pipeline_path)

        result['model_path'] = model_path
        result['pipeline_path'] = pipeline_path

        # 10. Save split report
        split_report_path = f"/app/output/split_report_{args.job_id}.json"
        save_split_report(df_train, df_val, df_test,
                         output_path=split_report_path,
                         time_col="TX_Time",
                         target_col="fraud")
        result['split_report_path'] = split_report_path

        # Output JSON to stdout (Java reads this)
        print(json.dumps(result, default=str))

    except Exception as e:
        logger.exception("Training failed")
        error_result = {
            'status': 'FAILED',
            'error': str(e)
        }
        print(json.dumps(error_result))
        sys.exit(1)


if __name__ == '__main__':
    main()