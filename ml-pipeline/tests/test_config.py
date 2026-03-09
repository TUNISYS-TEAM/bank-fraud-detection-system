import pytest
import json
from pathlib import Path
from ml_pipeline.config.model_config import ModelConfig
from ml_pipeline.config.settings import settings
from ml_pipeline.config.schemas import (
    TransactionFeatures,
    TransformedFeatures,
    PredictionOutput,
    TrainingRecord,
)


class TestModelConfig:
    def test_load_config(self, sample_config_json):
        cfg = ModelConfig(sample_config_json)
        assert cfg.get("global", "random_seed") == 42
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
        # Modify and check reload
        cfg._cfg["global"]["random_seed"] = 99
        cfg.save(out_path)
        cfg2 = ModelConfig(out_path)
        assert cfg2.get("global", "random_seed") == 99


class TestSettings:
    def test_settings_defaults(self):
        assert hasattr(settings, "postgres_host")
        assert settings.postgres_host == "localhost"


class TestSchemas:
    def test_transaction_features_valid(self):
        data = {
            "transaction_id": "tx1",
            "Account_Id": "C001",
            "TX_Time": "2025-01-01T12:00:00",
            "TX_Amount": 123.45,
            "Customer_Postal_Code": "2036",
            "Channel": "Online",
            "category": "shopping",
        }
        tx = TransactionFeatures(**data)
        assert tx.transaction_id == "tx1"

    def test_transaction_features_invalid(self):
        data = {"transaction_id": "tx1"}  # missing required
        with pytest.raises(ValueError):
            TransactionFeatures(**data)

    def test_prediction_output(self):
        out = PredictionOutput(
            transaction_id="tx1",
            fraud_probability=0.75,
            fraud_score=0.75,
            threshold=0.5,
            is_fraud=True,
            model_name="test",
            model_version="1.0",
        )
        assert out.is_fraud is True
        assert out.fraud_probability == 0.75