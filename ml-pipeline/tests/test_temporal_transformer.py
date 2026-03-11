import numpy as np
import pandas as pd
import pytest

from ml_pipeline.transformers.behavioral.temporal_features import TemporalFeatureTransformer


@pytest.fixture
def sample_behavioral_output():
    """Simulate output from ClientBehaviorTransformer."""
    np.random.seed(42)
    n = 20
    return pd.DataFrame(
        {
            "TX_Time": pd.date_range("2025-01-01", periods=n, freq="1h"),
            "TX_Amount": np.random.uniform(10, 1000, n),
            "client_AVG_TX_Amount": np.random.uniform(50, 500, n),
            "client_total_transactions": np.random.randint(1, 20, n),
            "client_is_unusual_region": np.random.choice([0, 1], n),
            "client_is_unusual_channel": np.random.choice([0, 1], n),
            "client_is_unusual_category": np.random.choice([0, 1], n),
            "client_hours_since_last_tx": np.random.uniform(0, 100, n),
            "client_time_since_last_tx": np.random.uniform(0, 360000, n),
            "client_total_days_since_first": np.random.uniform(0, 365, n),
            "client_AVG_TX_PerDay": np.random.uniform(0, 10, n),
        }
    )


class TestTemporalFeatureTransformer:
    def test_fit_transform(self, sample_behavioral_output):
        transformer = TemporalFeatureTransformer()
        transformer.fit(sample_behavioral_output)
        result = transformer.transform(sample_behavioral_output)

        assert isinstance(result, pd.DataFrame)
        assert result.shape[0] == sample_behavioral_output.shape[0]
        assert result.shape[1] > sample_behavioral_output.shape[1]

        expected = [
            "hour_sin",
            "hour_cos",
            "dow_sin",
            "dow_cos",
            "is_night",
            "fraud_risk_score",
            "fraud_risk_score_log",
        ]
        for col in expected:
            assert col in result.columns

    def test_spike_flags(self):
        df = pd.DataFrame(
            {
                "TX_Time": pd.date_range("2025-01-01", periods=4, freq="1h"),
                "TX_Amount": [1000, 2000, 500, 6000],
                "client_AVG_TX_Amount": [100, 100, 100, 100],
                "client_total_transactions": [5, 5, 5, 5],
                "client_is_unusual_region": [0, 0, 0, 0],
                "client_is_unusual_channel": [0, 0, 0, 0],
                "client_is_unusual_category": [0, 0, 0, 0],
                "client_hours_since_last_tx": [1, 1, 1, 1],
                "client_time_since_last_tx": [3600, 3600, 3600, 3600],
                "client_total_days_since_first": [1, 1, 1, 1],
                "client_AVG_TX_PerDay": [1, 1, 1, 1],
            }
        )

        transformer = TemporalFeatureTransformer(amount_spike_thresholds=[2, 5, 10])
        transformer.fit(df)
        result = transformer.transform(df)

        assert result.loc[0, "is_amount_spike_2x"] == 1
        assert result.loc[0, "is_amount_spike_5x"] == 1
        assert result.loc[0, "is_amount_spike_10x"] == 1

        assert result.loc[1, "is_amount_spike_2x"] == 1
        assert result.loc[1, "is_amount_spike_5x"] == 1
        assert result.loc[1, "is_amount_spike_10x"] == 1

        assert result.loc[2, "is_amount_spike_2x"] == 1
        assert result.loc[2, "is_amount_spike_5x"] == 1
        assert result.loc[2, "is_amount_spike_10x"] == 0

    def test_risk_score(self, sample_behavioral_output):
        transformer = TemporalFeatureTransformer()
        transformer.fit(sample_behavioral_output)
        result = transformer.transform(sample_behavioral_output)

        assert "fraud_risk_score" in result.columns
        assert "fraud_risk_score_log" in result.columns
        assert (result["fraud_risk_score"] >= 0).all()

    def test_multiday_detection(self, sample_behavioral_output):
        single_day = sample_behavioral_output.copy()
        single_day["TX_Time"] = pd.to_datetime("2025-01-01") + pd.to_timedelta(
            np.arange(len(single_day)), unit="h"
        )

        transformer = TemporalFeatureTransformer()
        transformer.fit(single_day)

        assert transformer.is_multiday_ is False

        result = transformer.transform(single_day)
        assert "is_weekend" in result.columns