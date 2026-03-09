"""
train_supervised.py
===================
Train a single supervised model with cross-validation, early stopping, and logging.
Useful for development or for training a specific model outside the full orchestrator.

Usage
-----
    python -m ml_pipeline.training.train_supervised \
        --model-type lightgbm \
        --data-dir exported_parquet/ \
        --out exported_models/
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.model_selection import StratifiedKFold

# Add parent directory to path to allow running as script
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from ml_pipeline.config.model_config import ModelConfig
from ml_pipeline.utils.logging_config import setup_logging

logger = None


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--model-type", required=True,
                   choices=["lightgbm", "xgboost", "random_forest", "catboost"],
                   help="Type of supervised model to train")
    p.add_argument("--data-dir", default="exported_parquet")
    p.add_argument("--out", default="exported_models")
    p.add_argument("--config", default="config/model_config.json")
    p.add_argument("--n-folds", type=int, default=5,
                   help="Number of cross‑validation folds")
    p.add_argument("--log", default=None)
    p.add_argument("--level", default="INFO")
    return p.parse_args()


def load_data(data_dir, model_type):
    d = Path(data_dir)
    # Determine which pipeline output to use
    if model_type == "catboost":
        prefix = "catboost"
    else:
        prefix = "tree"  # tree-based models use tree pipeline

    X_train = pd.read_parquet(d / f"train_{prefix}.parquet").fillna(0).values
    X_val = pd.read_parquet(d / f"val_{prefix}.parquet").fillna(0).values
    X_test = pd.read_parquet(d / f"test_{prefix}.parquet").fillna(0).values
    y_train = pd.read_parquet(d / "y_train.parquet")["fraud"].values
    y_val = pd.read_parquet(d / "y_val.parquet")["fraud"].values
    y_test = pd.read_parquet(d / "y_test.parquet")["fraud"].values

    return (X_train, y_train), (X_val, y_val), (X_test, y_test)


def cross_validate(model_class, X, y, n_folds=5, **model_kwargs):
    """Perform stratified k‑fold cross‑validation and return mean AUC."""
    from sklearn.metrics import roc_auc_score

    skf = StratifiedKFold(n_splits=n_folds, shuffle=True, random_state=42)
    aucs = []

    for fold, (train_idx, val_idx) in enumerate(skf.split(X, y)):
        X_tr, X_val_fold = X[train_idx], X[val_idx]
        y_tr, y_val_fold = y[train_idx], y[val_idx]

        model = model_class(**model_kwargs)
        model.fit(X_tr, y_tr, X_val_fold, y_val_fold)  # uses validation for early stopping
        y_pred = model.predict_proba(X_val_fold)
        auc = roc_auc_score(y_val_fold, y_pred)
        aucs.append(auc)
        logger.info(f"Fold {fold+1} AUC: {auc:.4f}")

    mean_auc = float(np.mean(aucs))
    std_auc = float(np.std(aucs))
    logger.info(f"CV AUC: {mean_auc:.4f} ± {std_auc:.4f}")
    return mean_auc, std_auc


def main():
    global logger
    args = parse_args()
    logger = setup_logging(level=args.level, log_file=args.log)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    cfg = ModelConfig(args.config)
    logger.info(f"Training {args.model_type} with config from {args.config}")

    # Load data
    (X_train, y_train), (X_val, y_val), (X_test, y_test) = load_data(args.data_dir, args.model_type)

    # Build model
    build_fn = getattr(cfg, f"build_{args.model_type}")
    model = build_fn()

    # Cross‑validation on training data (optional)
    if args.n_folds > 1:
        logger.info(f"Performing {args.n_folds}-fold CV on training set...")
        cv_auc, cv_std = cross_validate(
            model.__class__, X_train, y_train,
            n_folds=args.n_folds,
            **{k: v for k, v in model.__dict__.items() if not k.startswith("_")}
        )

    # Final training on full train + validation?
    # Here we follow the standard: train on train, validate on val, then evaluate on test.
    # But we could also combine train+val for final model. We'll keep as is.

    logger.info("Training final model on training set...")
    model.fit(X_train, y_train, X_val, y_val)

    # Evaluate
    metrics_train = model.evaluate(y_train, X_train, split_name="TRAIN")
    metrics_val = model.evaluate(y_val, X_val, split_name="VAL")
    metrics_test = model.evaluate(y_test, X_test, split_name="TEST")

    # Save model
    model_path = out / f"{args.model_type}.joblib"
    model.save(model_path)
    logger.info(f"Model saved to {model_path}")


if __name__ == "__main__":
    main()