from __future__ import annotations

import argparse
import logging
from pathlib import Path

import numpy as np
import pandas as pd

from shared_ml.config.model_config import ModelConfig
from shared_ml.utils.logging_config import setup_logging

logger = logging.getLogger(__name__)


def parse_args():
    parser = argparse.ArgumentParser(description="Train a single supervised model")
    parser.add_argument(
        "--model",
        required=True,
        choices=["lightgbm", "xgboost", "random_forest", "catboost"],
        help="Supervised model type to train",
    )
    parser.add_argument("--data_dir", default="exported_parquet")
    parser.add_argument("--out", default="exported_models")
    parser.add_argument("--config", default="config/model_config.json")
    parser.add_argument("--log", default=None)
    parser.add_argument("--level", default="INFO")
    return parser.parse_args()


def load_split(data_dir: str, feature_space: str, split: str) -> np.ndarray:
    """Load one feature matrix split."""
    path = Path(data_dir) / f"{split}_{feature_space}.parquet"
    if not path.exists():
        raise FileNotFoundError(f"Feature split not found: {path}")
    return pd.read_parquet(path).fillna(0).values


def load_labels(data_dir: str, split: str) -> np.ndarray:
    """Load one label split."""
    path = Path(data_dir) / f"y_{split}.parquet"
    if not path.exists():
        raise FileNotFoundError(f"Label split not found: {path}")
    return pd.read_parquet(path)["fraud"].values


def get_feature_space(model_name: str) -> str:
    """Map supervised model name to its feature space."""
    if model_name == "catboost":
        return "catboost"
    return "tree"


def main():
    args = parse_args()
    setup_logging(level=args.level, log_file=args.log)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    cfg = ModelConfig(args.config)
    model_name = args.model
    feature_space = get_feature_space(model_name)

    logger.info("Training %s using feature space '%s'", model_name, feature_space)

    X_train = load_split(args.data_dir, feature_space, "train")
    X_val = load_split(args.data_dir, feature_space, "val")
    y_train = load_labels(args.data_dir, "train")
    y_val = load_labels(args.data_dir, "val")

    build_fn = getattr(cfg, f"build_{model_name}")
    model = build_fn()
    model.fit(X_train, y_train, X_val, y_val)

    metrics = model.evaluate(y_val, X_val, split_name="VAL")
    logger.info("Validation metrics: %s", metrics)

    model_path = out / f"{model_name}.joblib"
    model.save(model_path)
    logger.info("Model saved → %s", model_path)

    print(f"\n{model_name} trained and saved to {model_path}")
    print(f"Threshold: {model.threshold_:.4f}")
    print(f"F2 (VAL):  {metrics['f2']:.4f}")
    print(f"AUC (VAL): {metrics['roc_auc']:.4f}")


if __name__ == "__main__":
    main()