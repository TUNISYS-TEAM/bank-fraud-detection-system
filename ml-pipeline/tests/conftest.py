# tests/conftest.py
import json
import tempfile
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from ml_pipeline.utils.postal_codes import get_resolver


@pytest.fixture(scope="session")
def sample_config_json():
    """Create a minimal model_config.json for testing."""
    config = {
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

    with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
        json.dump(config, f)
        path = f.name

    yield path
    Path(path).unlink(missing_ok=True)


@pytest.fixture(scope="session")
def sample_raw_data():
    """Generate a small DataFrame of raw transactions."""
    np.random.seed(42)
    n = 50
    postal_codes = ["1000", "2036", "3000", "4000", "5000"]

    df = pd.DataFrame(
        {
            "Account_Id": [f"A{str(i % 5).zfill(4)}" for i in range(n)],
            "TX_Time": pd.date_range("2025-01-01", periods=n, freq="1h"),
            "TX_Amount": np.random.lognormal(4, 0.5, n).round(2),
            "Customer_Postal_Code": [postal_codes[i % len(postal_codes)] for i in range(n)],
            "Channel": np.random.choice(["ATM", "Online", "Mobile", "POS"], n),
            "category": np.random.choice(["shopping", "food", "bills"], n),
            "fraud": np.random.choice([0, 1], n, p=[0.95, 0.05]),
        }
    )
    return df


@pytest.fixture
def postal_resolver():
    """Return a PostalCodeResolver instance."""
    return get_resolver()