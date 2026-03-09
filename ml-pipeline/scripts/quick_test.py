"""
quick_test.py
=============
Simple script to test that all core imports work and that the environment is set up correctly.
Run this after building the Docker image or setting up the virtual environment.
"""

import sys
from pathlib import Path

# Add project root to path if needed (when running as script)
project_root = Path(__file__).parent.parent
if str(project_root) not in sys.path:
    sys.path.insert(0, str(project_root))

def test_imports():
    """Try importing all major modules; raise ImportError if any fail."""
    modules = [
        "ml_pipeline.config.model_config",
        "ml_pipeline.config.settings",
        "ml_pipeline.config.schemas",
        "ml_pipeline.models.base_model",
        "ml_pipeline.models.supervised.lightgbm_model",
        "ml_pipeline.models.supervised.tree_models",
        "ml_pipeline.models.unsupervised.anomaly_models",
        "ml_pipeline.models.ensemble.ensemble",
        "ml_pipeline.transformers.behavioral.client_behavior",
        "ml_pipeline.transformers.behavioral.temporal_features",
        "ml_pipeline.transformers.encoders.categorical_encoder",
        "ml_pipeline.transformers.encoders.feature_selectors",
        "ml_pipeline.transformers.scalers.monetary_scaler",
        "ml_pipeline.pipeline.data_split",
        "ml_pipeline.pipeline.fraud_pipeline",
        "ml_pipeline.pipeline.onnx_exporter",
        "ml_pipeline.training.train_models",
        "ml_pipeline.training.validate_pipeline",
        "ml_pipeline.inference.contract",
        "ml_pipeline.inference.feature_schema",
        "ml_pipeline.inference.thresholding",
        "ml_pipeline.utils.logging_config",
        "ml_pipeline.utils.onnx_utils",
        "ml_pipeline.utils.postal_codes",
        "ml_pipeline.cli.main",
    ]

    print("Testing imports...")
    for mod_name in modules:
        try:
            __import__(mod_name)
            print(f"{mod_name}")
        except ImportError as e:
            print(f"{mod_name}: {e}")
            raise

    print("\nAll core imports succeeded.")


def test_postal_resolver():
    """Quick test of the postal code resolver."""
    from ml_pipeline.utils.postal_codes import get_resolver
    resolver = get_resolver()
    # Test a few known zip codes
    test_zips = ["1000", "2036", "3000", "9999"]
    for z in test_zips:
        gov = resolver.get_gov(z)
        region = resolver.get_region(z)
        print(f"  Zip {z}: gov={gov}, region={region}")


def test_logging():
    """Test logging setup."""
    from ml_pipeline.utils.logging_config import setup_logging
    logger = setup_logging(level="DEBUG")
    logger.debug("Debug message works")
    logger.info("Info message works")
    logger.warning("Warning message works")


if __name__ == "__main__":
    print("=" * 60)
    print("Quick test of fraud detection ML pipeline")
    print("=" * 60)

    try:
        test_imports()
        print("\n" + "=" * 60)
        print("Testing postal resolver...")
        test_postal_resolver()
        print("\n" + "=" * 60)
        print("Testing logging...")
        test_logging()
        print("\n" + "=" * 60)
        print("All tests passed!")
    except Exception as e:
        print(f"\nTest failed: {e}")
        sys.exit(1)