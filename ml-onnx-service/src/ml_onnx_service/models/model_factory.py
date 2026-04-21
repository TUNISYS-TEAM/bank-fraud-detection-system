from __future__ import annotations

import logging
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from shared_ml.config.model_config import ModelConfig

from ml_onnx_service.models.supervised.lightgbm_model import LightGBMFraudModel
from ml_onnx_service.models.supervised.tree_models import (
    CatBoostFraudModel,
    RandomForestFraudModel,
    XGBoostFraudModel,
)

logger = logging.getLogger(__name__)


class ModelFactory:
    """
    Factory pour instancier les modèles supervisés de ml-pipeline
    en utilisant la configuration de shared_ml.
    """

    @staticmethod
    def build_lightgbm(cfg: ModelConfig) -> LightGBMFraudModel:
        c = cfg.get("lightgbm")
        g = cfg.get("global_config")
        return LightGBMFraudModel(
            n_estimators=c.n_estimators,
            learning_rate=c.learning_rate,
            max_depth=c.max_depth,
            num_leaves=c.num_leaves,
            min_child_samples=c.min_child_samples,
            subsample=c.subsample,
            colsample_bytree=c.colsample_bytree,
            reg_alpha=c.reg_alpha,
            reg_lambda=c.reg_lambda,
            early_stopping_rounds=c.early_stopping_rounds,
            calibrate=c.calibrate,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    @staticmethod
    def build_xgboost(cfg: ModelConfig) -> XGBoostFraudModel:
        c = cfg.get("xgboost")
        g = cfg.get("global_config")
        return XGBoostFraudModel(
            n_estimators=c.n_estimators,
            max_depth=c.max_depth,
            learning_rate=c.learning_rate,
            subsample=c.subsample,
            colsample_bytree=c.colsample_bytree,
            early_stopping_rounds=c.early_stopping_rounds,
            calibrate=c.calibrate,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    @staticmethod
    def build_random_forest(cfg: ModelConfig) -> RandomForestFraudModel:
        c = cfg.get("random_forest")
        g = cfg.get("global_config")
        return RandomForestFraudModel(
            n_estimators=c.n_estimators,
            max_depth=c.max_depth,
            min_samples_leaf=c.min_samples_leaf,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    @staticmethod
    def build_catboost(cfg: ModelConfig, cat_features: list | None = None) -> CatBoostFraudModel:
        c = cfg.get("catboost")
        g = cfg.get("global_config")
        return CatBoostFraudModel(
            iterations=c.iterations,
            depth=c.depth,
            learning_rate=c.learning_rate,
            l2_leaf_reg=c.l2_leaf_reg,
            early_stopping_rounds=c.early_stopping_rounds,
            cat_features=cat_features,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    @classmethod
    def build_managed_model(cls, model_name: str, cfg: ModelConfig, **kwargs):
        """Construit un modèle par son nom (ex: 'lightgbm')."""
        builders = {
            "lightgbm": cls.build_lightgbm,
            "xgboost": cls.build_xgboost,
            "random_forest": cls.build_random_forest,
            "catboost": cls.build_catboost,
        }
        if model_name not in builders:
            raise ValueError(f"Unknown supervised model type: {model_name}")
        
        return builders[model_name](cfg, **kwargs)