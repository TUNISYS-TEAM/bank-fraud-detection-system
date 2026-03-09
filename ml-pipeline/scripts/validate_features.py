"""
validate_features.py
====================
Check consistency between raw transaction data and the expected feature schema.
Loads a sample of raw transactions, runs them through the pipeline,
and compares the resulting features with the FeatureSchema.
"""

import argparse
import sys
from pathlib import Path

# Add project root to path
project_root = Path(__file__).parent.parent
if str(project_root) not in sys.path:
    sys.path.insert(0, str(project_root))

import pandas as pd
import numpy as np

from ml_pipeline.inference.feature_schema import FeatureSchema
from ml_pipeline.pipeline.fraud_pipeline import FraudFeaturePipeline
from ml_pipeline.utils.logging_config import setup_logging

logger = setup_logging(level="INFO")


def load_sample_data(raw_path: Path, n_samples: int = 100):
    """Load raw transactions, either from a parquet file or generate sample."""
    if raw_path.exists():
        df = pd.read_parquet(raw_path)
        return df.head(n_samples)
    else:
        # Fallback: generate a few dummy rows
        from scripts.generate_sample_data import generate_sample_data
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            generate_sample_data(output_dir=tmpdir, n_transactions=n_samples, fraud_ratio=0.02)
            df = pd.read_parquet(Path(tmpdir) / "transactions.parquet")
        return df


def validate_features(pipeline_path: Path, raw_data_path: Path, n_samples: int = 100):
    """Run raw data through pipeline and check output against schema."""
    if not pipeline_path.exists():
        raise FileNotFoundError(f"Pipeline not found: {pipeline_path}")

    pipeline = FraudFeaturePipeline.load(pipeline_path)
    logger.info(f"Loaded pipeline: {pipeline}")

    # Load raw sample
    df_raw = load_sample_data(raw_data_path, n_samples)
    logger.info(f"Loaded {len(df_raw)} raw transactions")

    # Transform
    try:
        X_transformed = pipeline.transform(df_raw)
    except Exception as e:
        logger.error(f"Pipeline transform failed: {e}")
        return False

    # Check against schema
    expected_features = FeatureSchema.get_feature_names()
    n_expected = len(expected_features)

    if isinstance(X_transformed, pd.DataFrame):
        features_out = list(X_transformed.columns)
    else:  # numpy array
        features_out = [f"feature_{i}" for i in range(X_transformed.shape[1])]
        # If it's an array, we cannot compare names, only count
        if X_transformed.shape[1] != n_expected:
            logger.error(f"Feature count mismatch: pipeline produced {X_transformed.shape[1]}, expected {n_expected}")
            return False
        logger.info(f"Feature count OK: {X_transformed.shape[1]} features.")
        return True

    # Compare names
    missing = set(expected_features) - set(features_out)
    extra = set(features_out) - set(expected_features)

    if missing:
        logger.error(f"Missing features: {sorted(missing)[:10]}...")
    if extra:
        logger.warning(f"Extra features (not in schema): {sorted(extra)[:10]}...")

    if missing:
        return False

    logger.info("All expected features present.")
    return True


def main():
    parser = argparse.ArgumentParser(description="Validate pipeline output against feature schema.")
    parser.add_argument("--pipeline", required=True, help="Path to fitted pipeline .joblib file")
    parser.add_argument("--raw-data", default="data/samples/transactions.parquet",
                        help="Path to raw transaction parquet file (or sample will be generated)")
    parser.add_argument("--n-samples", type=int, default=100, help="Number of transactions to test")
    args = parser.parse_args()

    success = validate_features(Path(args.pipeline), Path(args.raw_data), args.n_samples)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()