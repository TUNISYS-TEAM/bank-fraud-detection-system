import pytest
from pydantic import ValidationError

from ml_pipeline.config.model_config import ModelConfig
from ml_pipeline.config.settings import settings
from ml_pipeline.config.config_schema import (
    GlobalConfig,
    LightGBMConfig,
    XGBoostConfig,
    RandomForestConfig,
    CatBoostConfig,
    AutoencoderConfig,
    AnomalyConfig,
    EnsembleConfig,
    FeatureConfig,
    ONNXExportConfig,
    FullModelConfig,
)


class TestModelConfig:
    def test_load_config(self, sample_config_json):
        cfg = ModelConfig(sample_config_json)
        assert cfg.get("global_config", "random_seed") == 42
        assert cfg.is_enabled("lightgbm") is True
        assert cfg.is_enabled("xgboost") is False

    def test_build_lightgbm(self, sample_config_json):
        cfg = ModelConfig(sample_config_json)
        model = cfg.build_lightgbm()
        assert model.name == "LightGBM"
        assert model.n_estimators == 10

    def test_build_all(self, sample_config_json):
        cfg = ModelConfig(sample_config_json)
        models = cfg.build_all()
        assert "lightgbm" in models
        assert "xgboost" not in models

    def test_save_config(self, tmp_path, sample_config_json):
        cfg = ModelConfig(sample_config_json)
        out_path = tmp_path / "test_config.json"
        cfg.save(out_path)
        assert out_path.exists()

        cfg._cfg.global_config.random_seed = 99
        cfg.save(out_path)

        cfg2 = ModelConfig(out_path)
        assert cfg2.get("global_config", "random_seed") == 99


class TestSettings:
    def test_settings_defaults(self):
        assert hasattr(settings, "postgres_host")
        assert isinstance(settings.postgres_host, str)
        assert settings.postgres_host


class TestConfigSchemas:
    def test_global_config_defaults(self):
        cfg = GlobalConfig()
        assert cfg.random_seed == 42
        assert cfg.threshold_metric == "fbeta"
        assert cfg.threshold_beta == 2.0

    def test_lightgbm_config_valid(self):
        cfg = LightGBMConfig(n_estimators=10, learning_rate=0.1, num_leaves=10)
        assert cfg.n_estimators == 10
        assert cfg.learning_rate == 0.1
        assert cfg.num_leaves == 10

    def test_lightgbm_config_invalid(self):
        with pytest.raises(ValidationError):
            LightGBMConfig(n_estimators=0)

    def test_xgboost_config_valid(self):
        cfg = XGBoostConfig(n_estimators=20, max_depth=4, learning_rate=0.05)
        assert cfg.n_estimators == 20
        assert cfg.max_depth == 4

    def test_random_forest_config_invalid(self):
        with pytest.raises(ValidationError):
            RandomForestConfig(min_samples_leaf=0)

    def test_catboost_config_invalid(self):
        with pytest.raises(ValidationError):
            CatBoostConfig(depth=20)

    def test_anomaly_config_invalid(self):
        with pytest.raises(ValidationError):
            AnomalyConfig(contamination=0.7)

    def test_ensemble_config_valid(self):
        cfg = EnsembleConfig(strategy="weighted", models=["lightgbm", "xgboost"])
        assert cfg.strategy == "weighted"
        assert cfg.models == ["lightgbm", "xgboost"]

    def test_feature_config_valid(self):
        cfg = FeatureConfig(enabled=True, ui_label="Amount")
        assert cfg.enabled is True
        assert cfg.ui_label == "Amount"

    def test_onnx_export_config_valid(self):
        cfg = ONNXExportConfig(enabled=True, opset_version=14, quantize=False)
        assert cfg.enabled is True
        assert cfg.opset_version == 14

    def test_full_model_config_valid(self):
        data = {
            "global": {
                "random_seed": 42,
                "threshold_metric": "fbeta",
                "threshold_beta": 2.0,
                "threshold_n_steps": 300,
                "n_jobs": 1,
            },
            "lightgbm": {
                "enabled": True,
                "n_estimators": 10,
                "learning_rate": 0.1,
                "max_depth": 3,
                "num_leaves": 10,
                "min_child_samples": 5,
                "subsample": 0.8,
                "colsample_bytree": 0.8,
                "reg_alpha": 0.0,
                "reg_lambda": 0.0,
                "early_stopping_rounds": 5,
                "calibrate": False,
            },
            "xgboost": {
                "enabled": False,
                "n_estimators": 10,
                "max_depth": 3,
                "learning_rate": 0.1,
                "subsample": 0.8,
                "colsample_bytree": 0.8,
                "early_stopping_rounds": 5,
                "calibrate": False,
            },
            "random_forest": {
                "enabled": False,
                "n_estimators": 10,
                "max_depth": 3,
                "min_samples_leaf": 5,
            },
            "catboost": {
                "enabled": False,
                "iterations": 10,
                "depth": 3,
                "learning_rate": 0.1,
                "l2_leaf_reg": 3.0,
                "early_stopping_rounds": 5,
            },
            "autoencoder": {
                "enabled": False,
                "encoding_dim": 4,
                "epochs": 1,
                "batch_size": 10,
            },
            "ecod": {
                "enabled": False,
                "contamination": 0.05,
            },
            "copod": {
                "enabled": False,
                "contamination": 0.05,
            },
            "ensemble": {
                "strategy": "weighted",
                "models": ["lightgbm"],
                "n_weight_restarts": 2,
                "beta": 2.0,
                "and_high_conf_threshold": 0.85,
            },
            "features": {
                "TX_Amount": {"enabled": True, "ui_label": "Amount"},
                "client_avg_tx_per_day": {"enabled": True, "ui_label": "Freq"},
            },
            "onnx_export": {
                "enabled": True,
                "opset_version": 14,
                "verify_equivalence": True,
                "target_platforms": ["cpu"],
                "quantize": False,
            },
        }

        cfg = FullModelConfig(**data)
        assert cfg.global_config.random_seed == 42
        assert cfg.lightgbm.enabled is True
        assert cfg.xgboost.enabled is False
        assert cfg.features["TX_Amount"].ui_label == "Amount"
        assert cfg.onnx_export.opset_version == 14