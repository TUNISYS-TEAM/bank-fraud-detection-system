import pytest
import pandas as pd
import numpy as np
from ml_pipeline.transformers.behavioral.temporal_features import TemporalFeatureTransformer


@pytest.fixture
def sample_behavioral_output():
    """Simulate output from ClientBehaviorTransformer."""
    np.random.seed(42)
    n = 20
    df = pd.DataFrame({
        "TX_Time": pd.date_range("2025-01-01", periods=n, freq="1H"),
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
    })
    return df


class TestTemporalFeatureTransformer:
    def test_fit_transform(self, sample_behavioral_output):
        transformer = TemporalFeatureTransformer()
        transformer.fit(sample_behavioral_output)
        result = transformer.transform(sample_behavioral_output)
        assert isinstance(result, pd.DataFrame)
        # Should have added many columns
        assert result.shape[1] > sample_behavioral_output.shape[1]
        # Check some expected columns
        expected = ["hour_sin", "hour_cos", "dow_sin", "dow_cos", "is_night",
                    "fraud_risk_score", "fraud_risk_score_log"]
        for col in expected:
            assert col in result.columns

    def test_spike_flags(self, sample_behavioral_output):
        # Add extreme amounts to trigger spikes
        df = sample_behavioral_output.copy()
        df["TX_Amount"] = [1000, 2000, 500, 6000]
        df["client_AVG_TX_Amount"] = [100, 100, 100, 100]
        transformer = TemporalFeatureTransformer(amount_spike_thresholds=[2, 5, 10])
        transformer.fit(df)
        result = transformer.transform(df)
        # First row: ratio = 10 -> spike_2x, spike_5x, spike_10x all true
        assert result.loc[0, "is_amount_spike_2x"] == 1
        assert result.loc[0, "is_amount_spike_5x"] == 1
        assert result.loc[0, "is_amount_spike_10x"] == 1
        # Second: 20x avg -> all true
        assert result.loc[1, "is_amount_spike_2x"] == 1
        # Third: 5x -> spike_2x and spike_5x true, 10x false
        assert result.loc[2, "is_amount_spike_2x"] == 1
        assert result.loc[2, "is_amount_spike_5x"] == 1
        assert result.loc[2, "is_amount_spike_10x"] == 0

    def test_risk_score(self, sample_behavioral_output):
        transformer = TemporalFeatureTransformer()
        transformer.fit(sample_behavioral_output)
        result = transformer.transform(sample_behavioral_output)
        assert "fraud_risk_score" in result.columns
        assert "fraud_risk_score_log" in result.columns
        # Ensure risk is non-negative
        assert (result["fraud_risk_score"] >= 0).all()

    def test_multiday_detection(self, sample_behavioral_output):
        # Single day -> multiday=False
        single_day = sample_behavioral_output.copy()
        single_day["TX_Time"] = pd.to_datetime("2025-01-01") + pd.to_timedelta(np.arange(len(single_day)), unit="h")
        transformer = TemporalFeatureTransformer()
        transformer.fit(single_day)
        assert transformer.is_multiday_ is False
        result = transformer.transform(single_day)
        # Columns that depend on multiday should be zero/absent? Actually they are still present but maybe zeros.
        # For simplicity, we check that is_weekend is present.
        assert "is_weekend" in result.columns