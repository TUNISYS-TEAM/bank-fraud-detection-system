import numpy as np
import pandas as pd
import pytest

from ml_pipeline.pipeline.data_split import temporal_split
from ml_pipeline.pipeline.fraud_pipeline import (
    FraudFeaturePipeline,
    create_catboost_pipeline,
    create_dl_pipeline,
    create_tree_pipeline,
)


@pytest.fixture
def sample_raw_df():
    np.random.seed(42)
    n = 100
    postal_codes = ["1000", "2036", "3000", "4000", "5000"]

    df = pd.DataFrame(
        {
            "Account_Id": [f"A{i % 5}" for i in range(n)],
            "TX_Time": pd.date_range("2025-01-01", periods=n, freq="1h"),
            "TX_Amount": np.random.lognormal(4, 0.5, n),
            "Customer_Postal_Code": [postal_codes[i % len(postal_codes)] for i in range(n)],
            "Channel": np.random.choice(["ATM", "Online", "Mobile"], n),
            "category": np.random.choice(["shopping", "food", "bills"], n),
            "fraud": np.random.choice([0, 1], n, p=[0.95, 0.05]),
        }
    )
    return df


class TestPipelineFactories:
    def test_tree_pipeline(self):
        pipe = create_tree_pipeline()
        assert "behavior" in pipe.named_steps
        assert "temporal" in pipe.named_steps
        assert "selector" in pipe.named_steps

    def test_catboost_pipeline(self):
        pipe = create_catboost_pipeline()
        assert "behavior" in pipe.named_steps
        assert "selector" in pipe.named_steps

    def test_dl_pipeline(self):
        pipe = create_dl_pipeline(scaler_type="robust")
        assert "behavior" in pipe.named_steps
        assert "scaler" in pipe.named_steps
        assert "to_float32" in pipe.named_steps


class TestFraudFeaturePipeline:
    def test_fit_transform(self, sample_raw_df):
        pipe = create_tree_pipeline()
        fp = FraudFeaturePipeline(pipe, name="test")
        fp.fit(sample_raw_df)

        X_trans = fp.transform(sample_raw_df)
        assert X_trans.shape[0] == len(sample_raw_df)

    def test_save_load(self, tmp_path, sample_raw_df):
        pipe = create_tree_pipeline()
        fp = FraudFeaturePipeline(pipe, name="test_save")
        fp.fit(sample_raw_df)

        path = tmp_path / "test_pipeline.joblib"
        fp.save(path)

        assert path.exists()
        assert path.with_suffix(".meta.json").exists()

        loaded = FraudFeaturePipeline.load(path)
        assert loaded.name == "test_save"

        X_loaded = loaded.transform(sample_raw_df)
        X_orig = fp.transform(sample_raw_df)

        if isinstance(X_loaded, pd.DataFrame):
            pd.testing.assert_frame_equal(X_loaded, X_orig)
        else:
            np.testing.assert_array_almost_equal(X_loaded, X_orig)

    def test_metadata_extraction(self, sample_raw_df):
        pipe = create_tree_pipeline()
        fp = FraudFeaturePipeline(pipe, name="test_meta")
        fp.fit(sample_raw_df)

        meta = fp._build_metadata()
        assert "name" in meta
        assert "feature_count" in meta
        assert "feature_names" in meta
        assert "pipeline_steps" in meta


class TestDataSplit:
    def test_temporal_split(self, sample_raw_df):
        train, val, test = temporal_split(
            sample_raw_df,
            train_ratio=0.7,
            val_ratio=0.15,
            time_col="TX_Time",
        )

        total = len(sample_raw_df)
        assert len(train) == int(total * 0.7)
        assert len(val) == int(total * 0.15)
        assert len(test) == total - len(train) - len(val)

        assert train["TX_Time"].max() <= val["TX_Time"].min()
        assert val["TX_Time"].max() <= test["TX_Time"].min()