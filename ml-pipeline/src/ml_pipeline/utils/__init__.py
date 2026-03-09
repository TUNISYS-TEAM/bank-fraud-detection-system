"""
Utility modules for the fraud detection pipeline.

Provides:
- logging_config: Centralised logging setup.
- onnx_utils: Helper functions for ONNX conversion and verification.
- postal_codes: Resolve Tunisian postal codes to governorates and regions.
"""

from .logging_config import setup_logging
from .onnx_utils import (
    convert_tree_model_to_onnx,
    convert_neural_model_to_onnx,
    verify_onnx_equivalence,
    get_onnx_input_shape,
    save_onnx_metadata,
)
from .postal_codes import PostalCodeResolver, get_resolver

__all__ = [
    "setup_logging",
    "convert_tree_model_to_onnx",
    "convert_neural_model_to_onnx",
    "verify_onnx_equivalence",
    "get_onnx_input_shape",
    "save_onnx_metadata",
    "PostalCodeResolver",
    "get_resolver",
]