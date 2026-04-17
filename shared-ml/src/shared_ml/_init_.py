"""Shared ML package for fraud detection training and inference."""

from shared_ml.config.model_config import ModelConfig
from shared_ml.contracts.prediction import (
    PredictRequest,
    BatchPredictItem,
    BatchPredictRequest,
    PredictResponse,
    BatchPredictResponse,
)
from shared_ml.contracts.transaction import (
    TransactionInput,
    PredictionOutput,
    BatchPredictionRequest,
    BatchPredictionResponse,
)
from shared_ml.preprocessing.behavioral.client_behavior import ClientBehaviorTransformer
from shared_ml.preprocessing.behavioral.temporal_features import TemporalFeatureTransformer
from shared_ml.preprocessing.encoders.categorical_encoder import SafeOneHotEncoder
from shared_ml.preprocessing.encoders.feature_selectors import (
    TreeFeatureSelector,
    CatBoostFeatureSelector,
    DLFeatureSelector,
)
from shared_ml.preprocessing.scalers.monetary_scaler import SafeScaler
from shared_ml.schemas.config_schema import FullModelConfig
from shared_ml.schemas.feature_schema import FeatureSchema
from shared_ml.serialization.joblib_io import atomic_save, atomic_load
from shared_ml.utils.logging_config import setup_logging
from shared_ml.utils.postal_codes import get_resolver, PostalCodeResolver
from shared_ml.utils.postal_codes import (  # helper functions for training scripts
    process_customer_postal_fit,
    process_customer_postal_transform,
    process_merchant_postal_fit,
    process_merchant_postal_transform,
)
from shared_ml.evaluation.data_split import temporal_split, save_split_report
from shared_ml.inference.thresholding import (
    find_threshold_by_cost,
    find_threshold_by_fbeta,
    find_threshold_for_recall,
    apply_threshold,
    adjust_threshold_with_business_rules,
)

__all__ = [
    "ModelConfig",
    "PredictRequest",
    "BatchPredictItem",
    "BatchPredictRequest",
    "PredictResponse",
    "BatchPredictResponse",
    "TransactionInput",
    "PredictionOutput",
    "BatchPredictionRequest",
    "BatchPredictionResponse",
    "ClientBehaviorTransformer",
    "TemporalFeatureTransformer",
    "SafeOneHotEncoder",
    "TreeFeatureSelector",
    "CatBoostFeatureSelector",
    "DLFeatureSelector",
    "SafeScaler",
    "FullModelConfig",
    "FeatureSchema",
    "atomic_save",
    "atomic_load",
    "setup_logging",
    "get_resolver",
    "PostalCodeResolver",
    "process_customer_postal_fit",
    "process_customer_postal_transform",
    "process_merchant_postal_fit",
    "process_merchant_postal_transform",
    "temporal_split",
    "save_split_report",
    "find_threshold_by_cost",
    "find_threshold_by_fbeta",
    "find_threshold_for_recall",
    "apply_threshold",
    "adjust_threshold_with_business_rules",
]