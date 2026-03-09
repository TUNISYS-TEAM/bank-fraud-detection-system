"""
Pipeline assembly module for fraud detection.

Provides:
- Temporal data splitting
- Sklearn pipeline factories for different model types
- FraudFeaturePipeline wrapper with metadata and atomic save/load
- ONNX exporter for model deployment
"""

from .data_split import temporal_split, save_split_report
from .fraud_pipeline import (
    create_tree_pipeline,
    create_catboost_pipeline,
    create_dl_pipeline,
    build_stateless_pipeline,
    build_full_inference_pipeline,
    FraudFeaturePipeline,
    build_all_pipelines,
    ToFloat32Transformer,
)
from .onnx_exporter import ONNXExporter

__all__ = [
    # data_split
    "temporal_split",
    "save_split_report",
    # fraud_pipeline
    "create_tree_pipeline",
    "create_catboost_pipeline",
    "create_dl_pipeline",
    "build_stateless_pipeline",
    "build_full_inference_pipeline",
    "FraudFeaturePipeline",
    "build_all_pipelines",
    "ToFloat32Transformer",
    # onnx_exporter
    "ONNXExporter",
]