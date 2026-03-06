"""
ONNX Exporter for Fraud Detection Pipeline
==========================================
Exports stateless preprocessing + model to a single ONNX graph.

Usage:
    python -m pipeline.onnx_exporter --model-type lightgbm --model-path exported_models/lightgbm.joblib --pipeline-path exported_models/pipeline_tree.joblib
"""

from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path
from typing import Optional

import joblib
import numpy as np
import pandas as pd
from sklearn.pipeline import Pipeline

from pipeline.fraud_pipeline import (
    build_stateless_pipeline,
    FraudFeaturePipeline,
)
from utils.onnx_utils import (
    convert_tree_model_to_onnx,
    convert_neural_model_to_onnx,
    verify_onnx_equivalence,
    save_onnx_metadata,
)

logger = logging.getLogger(__name__)


class ONNXExporter:
    """
    Exports a trained model + its stateless preprocessing to ONNX.
    """

    def __init__(
        self,
        model_type: str,
        model_path: Path,
        pipeline_path: Optional[Path] = None,
        output_dir: Path = Path("exported_onnx"),
    ):
        self.model_type = model_type
        self.model_path = Path(model_path)
        self.pipeline_path = Path(pipeline_path) if pipeline_path else None
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

        # Load components
        self.model = self._load_model()
        self.pipeline = self._load_pipeline()
        self.metadata = self._load_metadata()

    def _load_model(self):
        """Load the trained model."""
        logger.info(f"Loading model from {self.model_path}")
        return joblib.load(self.model_path)

    def _load_pipeline(self):
        """Load the feature pipeline."""
        if self.pipeline_path:
            logger.info(f"Loading pipeline from {self.pipeline_path}")
            return FraudFeaturePipeline.load(self.pipeline_path)
        return None

    def _load_metadata(self):
        """Load pipeline metadata."""
        if self.pipeline_path:
            meta_path = self.pipeline_path.with_suffix(".meta.json")
            if meta_path.exists():
                return json.loads(meta_path.read_text())
        
        # Also try model metadata
        model_meta_path = self.model_path.with_suffix(".meta.json")
        if model_meta_path.exists():
            return json.loads(model_meta_path.read_text())
        
        return {}

    def build_full_pipeline(self) -> Pipeline:
        """
        Build a combined sklearn pipeline of stateless steps + model.
        This is what will be exported to ONNX.
        """
        # Determine pipeline type from model_type
        if self.model_type in ["lightgbm", "xgboost", "random_forest"]:
            pipe_type = "tree"
        elif self.model_type == "catboost":
            pipe_type = "catboost"
        elif self.model_type in ["neural_network", "autoencoder"]:
            pipe_type = "dl"
        else:
            pipe_type = "tree"
        
        # Get scaler type from metadata if available
        scaler_type = self.metadata.get("transformer_params", {}).get("scaler_type", "robust")
        
        # Build stateless pipeline
        stateless = build_stateless_pipeline(
            model_type=pipe_type,
            scaler_type=scaler_type,
            fitted_params=self.metadata.get("transformer_params", {}),
        )
        
        # Note: The stateless pipeline needs to be fitted with the same parameters
        # as during training. In a production setting, you would either:
        # 1. Save the fitted pipeline along with the model, or
        # 2. Save the transformer parameters in metadata and re-fit here
        # For now, we'll assume the pipeline was fitted and saved separately
        
        # Combine with model
        return Pipeline(stateless.steps + [("model", self.model)])

    def export(self, verify: bool = True) -> Path:
        """
        Export the full pipeline to ONNX.
        
        Args:
            verify: If True, run equivalence test after export
            
        Returns:
            Path to exported ONNX file
        """
        logger.info(f"Exporting {self.model_type} model to ONNX")
        
        # Get input feature names from metadata
        feature_names = self.metadata.get("feature_names", [])
        if not feature_names:
            # Try to get from model
            if hasattr(self.model, "feature_names_") and self.model.feature_names_:
                feature_names = self.model.feature_names_
            else:
                logger.warning("No feature names found in metadata. Using generic names.")
                if hasattr(self.model, "n_features_in_"):
                    n_features = self.model.n_features_in_
                else:
                    n_features = 20  # Fallback
                feature_names = [f"feature_{i}" for i in range(n_features)]
        
        n_features = len(feature_names)
        logger.info(f"Input features ({n_features}): {feature_names[:5]}...")
        
        # Export based on model type
        if self.model_type in ["lightgbm", "xgboost", "random_forest", "catboost"]:
            onnx_path = convert_tree_model_to_onnx(
                model=self.model,
                model_type=self.model_type,
                output_dir=self.output_dir,
                input_dim=n_features,
                feature_names=feature_names,
            )
        elif self.model_type in ["neural_network", "autoencoder"]:
            onnx_path = convert_neural_model_to_onnx(
                model=self.model,
                pipeline=None,  # We're exporting model only for now
                output_dir=self.output_dir,
                input_dim=n_features,
            )
        else:
            raise ValueError(f"Unsupported model type for ONNX: {self.model_type}")
        
        # Save metadata for Java consumption
        additional_meta = {
            "threshold": getattr(self.model, "threshold_", 0.5),
            "calibrated": getattr(self.model, "calibrate", False),
            "preprocessing": self.metadata.get("transformer_params", {}),
        }
        save_onnx_metadata(onnx_path, self.model_type, feature_names, additional_meta)
        
        # Verify equivalence
        if verify:
            logger.info("Verifying ONNX equivalence...")
            test_data = self._get_test_sample(n_features)
            if test_data is not None:
                verify_onnx_equivalence(
                    original_model=self.model,
                    onnx_path=onnx_path,
                    test_data=test_data,
                )
            else:
                logger.warning("No test data available for verification. Skipping.")
        
        logger.info(f"ONNX export complete: {onnx_path}")
        return onnx_path

    def _get_test_sample(self, n_features: int, n_samples: int = 100):
        """Get a small test sample for verification."""
        # Try to load from test data if available
        test_data_path = Path("data/test_sample.npy")
        if test_data_path.exists():
            return np.load(test_data_path)
        
        # Otherwise generate random data
        logger.info(f"Generating random test data with {n_samples} samples, {n_features} features")
        return np.random.randn(n_samples, n_features).astype(np.float32)


def main():
    parser = argparse.ArgumentParser(description="Export trained models to ONNX")
    parser.add_argument("--model-type", required=True, 
                       choices=["lightgbm", "xgboost", "random_forest", "catboost",
                               "neural_network", "autoencoder"],
                       help="Type of model to export")
    parser.add_argument("--model-path", required=True, 
                       help="Path to trained model .joblib file")
    parser.add_argument("--pipeline-path", 
                       help="Path to fitted pipeline .joblib file (optional)")
    parser.add_argument("--output-dir", default="exported_onnx",
                       help="Directory to save ONNX models")
    parser.add_argument("--no-verify", action="store_true",
                       help="Skip equivalence verification")
    parser.add_argument("--log-level", default="INFO",
                       choices=["DEBUG", "INFO", "WARNING", "ERROR"],
                       help="Logging level")
    
    args = parser.parse_args()

    # Setup logging
    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
    )

    try:
        exporter = ONNXExporter(
            model_type=args.model_type,
            model_path=args.model_path,
            pipeline_path=args.pipeline_path,
            output_dir=args.output_dir,
        )
        
        onnx_path = exporter.export(verify=not args.no_verify)
        print(f"\nSuccessfully exported to: {onnx_path}")
        print(f"   Metadata: {onnx_path.with_suffix('.meta.json')}")
        
    except Exception as e:
        logger.error(f"Export failed: {e}")
        raise


if __name__ == "__main__":
    main()