#!/usr/bin/env python3
"""
Train a Random Forest model using the full fraud detection pipeline.
"""

import argparse
import json
import logging
import sys
import time
import os
from pathlib import Path
from datetime import datetime

import numpy as np
import pandas as pd
import joblib

sys.path.insert(0, '/app/src')
from ml_pipeline.pipeline.fraud_pipeline import FraudFeaturePipeline, create_tree_pipeline
from ml_pipeline.pipeline.data_split import temporal_split, save_split_report
from ml_pipeline.models.supervised.tree_models import RandomForestFraudModel
from ml_pipeline.inference.thresholding import (
    find_threshold_by_cost,
    find_threshold_by_fbeta,
    find_threshold_for_recall,
)

# Configure logging to stderr
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
    stream=sys.stderr
)
logger = logging.getLogger(__name__)


def make_json_safe(obj):
    """Recursively convert all dict keys to strings and all values to JSON-safe types.

    This prevents json.dumps from failing or producing invalid JSON when the
    object graph contains numpy scalars, numpy arrays, tuple keys, or other
    non-standard Python types (e.g. feature names from sklearn ColumnTransformer
    which are often tuples such as ('pipeline-1', 'feature_x')).
    """
    if isinstance(obj, dict):
        return {
            str(k).replace("'", "").replace('"', ''): make_json_safe(v)
            for k, v in obj.items()
        }
    elif isinstance(obj, (list, tuple)):
        return [make_json_safe(i) for i in obj]
    elif isinstance(obj, np.integer):
        return int(obj)
    elif isinstance(obj, np.floating):
        return float(obj)
    elif isinstance(obj, np.ndarray):
        return obj.tolist()
    else:
        return obj


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

    # Redirect stdout to stderr for the entire duration.
    # This ensures that any stray print calls (including from imported libraries)
    # go to stderr instead of stdout.
    original_stdout = sys.stdout
    sys.stdout = sys.stderr

    # Prepare a clean file object for the final stdout write.
    # We'll use this at the end to output the JSON.
    stdout_fd = os.fdopen(os.dup(1), 'w')

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

        # 4. Build and fit the feature pipeline ON TRAINING DATA ONLY
        logger.info("Creating and fitting feature pipeline on training data only...")
        pipeline = create_tree_pipeline()
        feature_pipeline = FraudFeaturePipeline(pipeline, name="random_forest")
        feature_pipeline.fit(df_train)

        # 5. Transform each split correctly.
        logger.info("Transforming training set (strict as-of, no history injection)...")
        X_train = feature_pipeline.transform_train(df_train)

        logger.info("Transforming validation set...")
        X_val = feature_pipeline.transform(df_val)

        logger.info("Transforming test set...")
        X_test = feature_pipeline.transform(df_test)

        y_train = df_train['fraud'].values
        y_val   = df_val['fraud'].values
        y_test  = df_test['fraud'].values

        logger.info(f"Features shape: train {X_train.shape}, val {X_val.shape}, test {X_test.shape}")
        logger.info(f"Fraud rate — train: {y_train.mean():.4f}, val: {y_val.mean():.4f}, test: {y_test.mean():.4f}")

        # 6. Train Random Forest
        logger.info("Training Random Forest model...")
        training_start_time = datetime.now()
        model = RandomForestFraudModel(
            n_estimators=hp.get('n_estimators', 100),
            max_depth=hp.get('max_depth', 10),
            min_samples_leaf=hp.get('min_samples_leaf', 10),
            threshold_metric='fbeta',
            beta=2.0
        )
        if 'min_samples_split' in hp:
            model.min_samples_split = hp['min_samples_split']

        start_time = time.time()
        model.fit(X_train, y_train, X_val, y_val)
        train_time = time.time() - start_time
        training_end_time = datetime.now()

        logger.info(
            f"Model fitted — auto-tuned threshold: {model.threshold_:.4f} "
            f"(via fbeta beta=2.0 on validation set)"
        )

        # 7. Compute alternative thresholds on the validation set.
        val_probs = model.predict_proba(X_val)

        threshold_fbeta, score_fbeta = find_threshold_by_fbeta(
            y_val, val_probs, beta=2.0
        )
        threshold_cost, min_cost = find_threshold_by_cost(
            y_val, val_probs, cost_fp=1.0, cost_fn=10.0
        )
        try:
            threshold_recall = find_threshold_for_recall(
                y_val, val_probs, target_recall=0.85, max_fpr=0.20
            )
        except ValueError as e:
            logger.warning(f"Recall+FPR constraint unsatisfiable: {e} — retrying without FPR cap")
            try:
                threshold_recall = find_threshold_for_recall(
                    y_val, val_probs, target_recall=0.85
                )
            except ValueError:
                logger.warning("recall>=0.85 unsatisfiable — falling back to fbeta threshold")
                threshold_recall = threshold_fbeta

        logger.info(
            f"Thresholds — fbeta: {threshold_fbeta:.4f} (F2={score_fbeta:.4f})  "
            f"cost: {threshold_cost:.4f} (total_cost={min_cost:.0f})  "
            f"recall>=0.85: {threshold_recall:.4f}"
        )

        # 8. Evaluate on test set
        metrics = model.evaluate(y_test, X_test, split_name="TEST")
        logger.info(f"Test metrics: {metrics}")

        cm_tn = metrics.get('true_negatives', 0)
        cm_fp = metrics.get('false_positives', 0)
        cm_fn = metrics.get('false_negatives', 0)
        cm_tp = metrics.get('true_positives', 0)

        # 9. Prepare output JSON.
        # Note: make_json_safe() handles all type conversion recursively, including:
        #   - numpy scalars (np.integer, np.floating, np.ndarray)
        #   - tuple dict keys from sklearn ColumnTransformer (e.g. ('pipeline-1', 'feat'))
        #   - any other non-string keys that would produce invalid JSON
        # Do NOT use json.dumps(..., default=str) — it silently produces single-quoted
        # representations for tuple keys, which is invalid JSON.
        result = {
            'status': 'COMPLETED',
            'job_id': args.job_id,
            'dataset_id': args.dataset_id,
            'hyperparameters_used': {
                'n_estimators': model.n_estimators,
                'max_depth': model.max_depth,
                'min_samples_split': getattr(model, 'min_samples_split', 2),
                'min_samples_leaf': model.min_samples_leaf,
                'random_seed': args.random_seed,
            },
            'metrics': {
                'f1_score': metrics.get('f1', 0),
                'precision': metrics.get('precision', 0),
                'recall': metrics.get('recall', 0),
                'auc_roc': metrics.get('roc_auc', 0),
            },
            'confusion_matrix': {
                'true_negatives': cm_tn,
                'false_positives': cm_fp,
                'false_negatives': cm_fn,
                'true_positives': cm_tp,
            },
            'feature_importance': model.get_feature_importance(),
            'training_stats': {
                'train_rows': X_train.shape[0],
                'val_rows': X_val.shape[0],
                'test_rows': X_test.shape[0],
                'fraud_rate_test': float(np.mean(y_test)),
                'threshold_fbeta': float(threshold_fbeta),
                'threshold_cost': float(threshold_cost),
                'threshold_recall': float(threshold_recall),
                'best_threshold': float(model.threshold_),
            },
            'start_time': training_start_time.isoformat(),
            'end_time': training_end_time.isoformat(),
            'duration_seconds': train_time,
        }

        # 10. Save model and pipeline
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

        # 11. Export model to ONNX inline — avoids a separate subprocess round-trip.
        # to_onnx() is defined on FraudBaseModel and implemented by RandomForestFraudModel.
        # We pass feature_names from the pipeline so the ONNX graph is self-documenting.
        onnx_path = None
        try:
            onnx_dir = Path("/app/output/onnx")
            onnx_dir.mkdir(parents=True, exist_ok=True)
            onnx_filename = f"random_forest_{args.job_id}.onnx"
            onnx_path = str(onnx_dir / onnx_filename)

            # Retrieve feature names produced by the fitted pipeline if available
            feature_names = None
            try:
                feature_names = feature_pipeline.get_feature_names_out()
            except Exception:
                pass  # feature names are optional — ONNX export works without them

            model.to_onnx(onnx_path, feature_names=feature_names)
            result['onnx_path'] = onnx_path
            logger.info(f"ONNX model exported → {onnx_path}")
        except Exception as onnx_err:
            # ONNX export failure is non-fatal — training result is still valid
            logger.warning(f"ONNX export failed (non-fatal): {onnx_err}")
            result['onnx_path'] = None

        # 12. Save split report
        split_report_path = f"/app/output/split_report_{args.job_id}.json"
        save_split_report(
            df_train, df_val, df_test,
            output_path=split_report_path,
            time_col="TX_Time",
            target_col="fraud",
        )
        result['split_report_path'] = split_report_path

        # 13. Write the final JSON directly to the original stdout.
        # make_json_safe() is called here (not default=str) to guarantee valid JSON output.
        stdout_fd.write(json.dumps(make_json_safe(result)) + '\n')
        stdout_fd.flush()

    except Exception as e:
        logger.exception("Training failed")
        error_result = {
            'status': 'FAILED',
            'error': str(e),
        }
        stdout_fd.write(json.dumps(error_result) + '\n')
        stdout_fd.flush()
        sys.exit(1)

    finally:
        # Restore original stdout (not strictly necessary but good practice)
        sys.stdout = original_stdout
        stdout_fd.close()


if __name__ == '__main__':
    main()