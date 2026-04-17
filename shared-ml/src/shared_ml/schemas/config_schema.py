from __future__ import annotations

from typing import Literal
from pydantic import BaseModel, Field


class GlobalConfig(BaseModel):
    random_seed: int = 42
    threshold_metric: str = "fbeta"
    threshold_beta: float = 2.0
    threshold_n_steps: int = 300
    n_jobs: int = -1


class LightGBMConfig(BaseModel):
    enabled: bool = True
    n_estimators: int = Field(1000, ge=1)
    learning_rate: float = Field(0.02, gt=0, le=1)
    max_depth: int = Field(8, ge=-1)
    num_leaves: int = Field(50, ge=2)
    min_child_samples: int = Field(80, ge=1)
    subsample: float = Field(0.8, gt=0, le=1)
    colsample_bytree: float = Field(0.8, gt=0, le=1)
    reg_alpha: float = 0.1
    reg_lambda: float = 0.1
    early_stopping_rounds: int = 30
    calibrate: bool = True


class XGBoostConfig(BaseModel):
    enabled: bool = True
    n_estimators: int = Field(500, ge=1)
    max_depth: int = Field(8, ge=1)
    learning_rate: float = Field(0.02, gt=0, le=1)
    subsample: float = Field(0.8, gt=0, le=1)
    colsample_bytree: float = Field(0.8, gt=0, le=1)
    early_stopping_rounds: int = 30
    calibrate: bool = True


class RandomForestConfig(BaseModel):
    enabled: bool = True
    n_estimators: int = Field(200, ge=1)
    max_depth: int = Field(15, ge=1)
    min_samples_leaf: int = Field(10, ge=1)


class CatBoostConfig(BaseModel):
    enabled: bool = True
    iterations: int = Field(500, ge=1)
    depth: int = Field(6, ge=1, le=16)
    learning_rate: float = Field(0.05, gt=0, le=1)
    l2_leaf_reg: float = 3.0
    early_stopping_rounds: int = 30


class AutoencoderConfig(BaseModel):
    enabled: bool = True
    encoding_dim: int = 32
    epochs: int = 20
    batch_size: int = 1024


class AnomalyConfig(BaseModel):
    enabled: bool = True
    contamination: float = Field(0.05, gt=0, lt=0.5)


class EnsembleConfig(BaseModel):
    strategy: Literal["weighted", "and", "or_boost"] = "weighted"
    models: list[str] = ["lightgbm", "xgboost", "ecod"]
    n_weight_restarts: int = 10
    beta: float = 2.0
    and_high_conf_threshold: float = 0.85


class FeatureConfig(BaseModel):
    enabled: bool = True
    ui_label: str


class ONNXExportConfig(BaseModel):
    enabled: bool = True
    opset_version: int = 14
    verify_equivalence: bool = True
    target_platforms: list[str] = ["cpu"]
    quantize: bool = False


class FullModelConfig(BaseModel):
    global_config: GlobalConfig = Field(..., alias="global")
    lightgbm: LightGBMConfig
    xgboost: XGBoostConfig
    random_forest: RandomForestConfig
    catboost: CatBoostConfig
    autoencoder: AutoencoderConfig
    ecod: AnomalyConfig
    copod: AnomalyConfig
    ensemble: EnsembleConfig
    features: dict[str, FeatureConfig]
    onnx_export: ONNXExportConfig

    model_config = {
        "populate_by_name": True
    }