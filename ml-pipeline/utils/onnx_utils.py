"""
ONNX Utilities for Fraud Detection
===================================
Helper functions for converting models to ONNX and verifying equivalence.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional, Union

import numpy as np
import onnx
import onnxruntime as ort
import pandas as pd
from sklearn.pipeline import Pipeline

logger = logging.getLogger(__name__)


def convert_tree_model_to_onnx(
    model: Union[Pipeline, object],
    model_type: str,
    output_dir: Path,
    input_dim: int,
    feature_names: Optional[list] = None,
) -> Path:
    """
    Convert tree-based models (LightGBM, XGBoost, RF, CatBoost) to ONNX.
    
    Args:
        model: Trained model or pipeline containing model
        model_type: Type of model ('lightgbm', 'xgboost', 'random_forest', 'catboost')
        output_dir: Directory to save ONNX file
        input_dim: Number of input features
        feature_names: Names of input features
        
    Returns:
        Path to saved ONNX file
    """
    output_path = output_dir / f"{model_type}_pipeline.onnx"
    
    # Extract the actual model if pipeline is provided
    if isinstance(model, Pipeline):
        if "model" in model.named_steps:
            model = model.named_steps["model"]
        else:
            # Assume last step is the model
            model = model.steps[-1][1]
    
    if model_type == "lightgbm":
        try:
            import onnxmltools
            from skl2onnx.common.data_types import FloatTensorType
        except ImportError:
            raise ImportError("Please install onnxmltools and skl2onnx: pip install onnxmltools skl2onnx")
        
        # Check if it's a calibrated model
        if hasattr(model, "_calibrated") and model._calibrated is not None:
            logger.warning("Calibrated model detected. Exporting base LightGBM model.")
            if hasattr(model, "get_booster"):
                booster = model.get_booster()
            else:
                booster = model._model.booster_
        else:
            if hasattr(model, "get_booster"):
                booster = model.get_booster()
            else:
                booster = model._model.booster_
        
        initial_types = [('float_input', FloatTensorType([None, input_dim]))]
        onnx_model = onnxmltools.convert_lightgbm(booster, initial_types=initial_types)
        
        with open(output_path, "wb") as f:
            f.write(onnx_model.SerializeToString())
        
    elif model_type == "xgboost":
        try:
            import onnxmltools
            from skl2onnx.common.data_types import FloatTensorType
        except ImportError:
            raise ImportError("Please install onnxmltools and skl2onnx")
        
        initial_types = [('float_input', FloatTensorType([None, input_dim]))]
        onnx_model = onnxmltools.convert_xgboost(model, initial_types=initial_types)
        
        with open(output_path, "wb") as f:
            f.write(onnx_model.SerializeToString())
        
    elif model_type == "random_forest":
        try:
            from skl2onnx import convert_sklearn
            from skl2onnx.common.data_types import FloatTensorType
        except ImportError:
            raise ImportError("Please install skl2onnx")
        
        initial_types = [('float_input', FloatTensorType([None, input_dim]))]
        onnx_model = convert_sklearn(model, initial_types=initial_types)
        
        with open(output_path, "wb") as f:
            f.write(onnx_model.SerializeToString())
        
    elif model_type == "catboost":
        # CatBoost has native ONNX export
        if not hasattr(model, "save_model"):
            raise AttributeError("CatBoost model doesn't have save_model method")
        
        model.save_model(
            str(output_path),
            format="onnx",
            export_parameters={
                'onnx_domain': 'ai.catboost',
                'feature_names': feature_names or [f"f{i}" for i in range(input_dim)]
            }
        )
    
    else:
        raise ValueError(f"Unsupported tree model: {model_type}")
    
    logger.info(f"ONNX model saved to {output_path}")
    return output_path


def convert_neural_model_to_onnx(
    model,
    pipeline: Optional[Pipeline],
    output_dir: Path,
    input_dim: int,
) -> Path:
    """
    Convert PyTorch neural models to ONNX.
    
    Args:
        model: Trained neural model (should have a .model attribute)
        pipeline: Optional preprocessing pipeline
        output_dir: Directory to save ONNX file
        input_dim: Number of input features
        
    Returns:
        Path to saved ONNX file
    """
    try:
        import torch
    except ImportError:
        raise ImportError("Please install torch: pip install torch")
    
    output_path = output_dir / "neural_pipeline.onnx"
    
    # Extract the torch model
    if hasattr(model, "model"):
        torch_model = model.model
    elif hasattr(model, "_model"):
        torch_model = model._model
    else:
        torch_model = model
    
    # Create dummy input
    dummy_input = torch.randn(1, input_dim, dtype=torch.float32)
    
    # Export the torch model
    torch.onnx.export(
        torch_model,
        dummy_input,
        output_path,
        input_names=['float_input'],
        output_names=['score'],
        dynamic_axes={
            'float_input': {0: 'batch_size'},
            'score': {0: 'batch_size'}
        },
        opset_version=14,
    )
    
    logger.info(f"Neural model exported to ONNX: {output_path}")
    return output_path


def verify_onnx_equivalence(
    original_model,
    onnx_path: Path,
    test_data: Optional[np.ndarray] = None,
    tolerance: float = 1e-5,
) -> bool:
    """
    Verify that ONNX model produces same outputs as original model.
    
    Args:
        original_model: Original trained model
        onnx_path: Path to ONNX model
        test_data: Test data for verification (generated if None)
        tolerance: Maximum allowed difference
        
    Returns:
        True if equivalent within tolerance
    """
    if test_data is None:
        # Generate random test data
        if hasattr(original_model, "feature_names_") and original_model.feature_names_:
            n_features = len(original_model.feature_names_)
        elif hasattr(original_model, "n_features_in_"):
            n_features = original_model.n_features_in_
        else:
            n_features = 20  # Default fallback
        
        test_data = np.random.randn(100, n_features).astype(np.float32)
        logger.info(f"Generated random test data with shape {test_data.shape}")
    
    # Get original predictions
    try:
        if hasattr(original_model, "predict_proba"):
            original_scores = original_model.predict_proba(test_data)
            if original_scores.ndim == 2:
                original_scores = original_scores[:, 1]
        else:
            original_scores = original_model.predict(test_data)
    except Exception as e:
        logger.error(f"Error getting original predictions: {e}")
        return False
    
    # Get ONNX predictions
    try:
        ort_session = ort.InferenceSession(str(onnx_path))
        input_name = ort_session.get_inputs()[0].name
        onnx_input = {input_name: test_data}
        onnx_scores = ort_session.run(None, onnx_input)[0]
        
        if onnx_scores.ndim == 2:
            onnx_scores = onnx_scores[:, 1]
    except Exception as e:
        logger.error(f"Error running ONNX model: {e}")
        return False
    
    # Compare
    if len(original_scores) != len(onnx_scores):
        logger.error(f"Output length mismatch: {len(original_scores)} vs {len(onnx_scores)}")
        return False
    
    max_diff = np.max(np.abs(original_scores - onnx_scores))
    mean_diff = np.mean(np.abs(original_scores - onnx_scores))
    
    logger.info(f"Max difference between original and ONNX: {max_diff:.6f}")
    logger.info(f"Mean difference: {mean_diff:.6f}")
    
    if max_diff > tolerance:
        logger.error(f"❌ Equivalence check failed! Max diff {max_diff:.6f} > {tolerance}")
        return False
    
    logger.info(f"✅ ONNX equivalence verified (max diff: {max_diff:.6f})")
    return True


def get_onnx_input_shape(onnx_path: Path) -> tuple:
    """Get expected input shape from ONNX model."""
    model = onnx.load(str(onnx_path))
    input_shape = model.graph.input[0].type.tensor_type.shape.dim
    return tuple(dim.dim_value for dim in input_shape)


def save_onnx_metadata(
    onnx_path: Path,
    model_type: str,
    feature_names: list[str],
    additional_metadata: dict = None
) -> Path:
    """Save metadata for ONNX model (for Java consumption)."""
    meta = {
        "model_type": model_type,
        "onnx_path": str(onnx_path),
        "input_features": feature_names,
        "input_dim": len(feature_names),
        "output_type": "probability",
        "exported_at": pd.Timestamp.now().isoformat(),
    }
    
    if additional_metadata:
        meta.update(additional_metadata)
    
    meta_path = onnx_path.with_suffix(".meta.json")
    meta_path.write_text(json.dumps(meta, indent=2))
    logger.info(f"ONNX metadata saved to {meta_path}")
    
    return meta_path