import pytest
import pandas as pd
import numpy as np
from ml_pipeline.transformers.behavioral.client_behavior import ClientBehaviorTransformer


@pytest.fixture
def sample_transactions():
    """Create a small DataFrame with transactions for a few clients."""
    np.random.seed(42)
    # Three clients with sequential transactions
    data = {
        "Account_Id": ["C1"] * 5 + ["C2"] * 3 + ["C3"] * 4,
        "TX_Time": pd.to_datetime([
            "2025-01-01 10:00", "2025-01-01 11:00", "2025-01-02 10:00", "2025-01-03 10:00", "2025-01-03 12:00",
            "2025-01-01 09:00", "2025-01-02 09:00", "2025-01-03 09:00",
            "2025-01-01 08:00", "2025-01-02 08:00", "2025-01-03 08:00", "2025-01-04 08:00",
        ]),
        "TX_Amount": [100, 150, 200, 300, 50, 80, 120, 90, 500, 520, 480, 600],
        "Customer_Postal_Code": ["1000"] * 5 + ["2036"] * 3 + ["3000"] * 4,
        "Channel": ["ATM"] * 5 + ["Online"] * 3 + ["Mobile"] * 4,
        "category": ["shopping"] * 5 + ["food"] * 3 + ["bills"] * 4,
    }
    df = pd.DataFrame(data)
    # Add a fraud column (not used by transformer but may be present)
    df["fraud"] = [0] * (len(df) - 1) + [1]
    return df


class TestClientBehaviorTransformer:
    def test_fit_transform_shape(self, sample_transactions):
        transformer = ClientBehaviorTransformer(smoothing_days=1, default_time_gap_days=30, strict_as_of=True)
        transformer.fit(sample_transactions)
        result = transformer.transform(sample_transactions)
        assert isinstance(result, pd.DataFrame)
        expected_cols = [
            "TX_Amount",
            "client_AVG_TX_Amount", "client_AVG_TX_PerDay",
            "client_amount_deviation", "client_abs_amount_deviation",
            "client_amount_max_ratio", "client_amount_z_score",
            "client_is_new_max", "client_is_new_min",
            "client_total_transactions",
            "client_time_since_last_tx", "client_hours_since_last_tx",
            "client_total_days_since_first",
            "client_usual_region", "client_is_unusual_region", "client_region_freq",
            "client_usual_channel", "client_is_unusual_channel", "client_channel_freq",
            "client_usual_category", "client_is_unusual_category", "client_category_freq",
            "client_risk_score",
        ]
        assert all(col in result.columns for col in expected_cols)
        assert len(result) == len(sample_transactions)

    def test_as_of_correctness(self, sample_transactions):
        """Test that first transaction of each client has zero history."""
        transformer = ClientBehaviorTransformer()
        transformer.fit(sample_transactions)
        result = transformer.transform(sample_transactions)
        # For each client, the first transaction should have total_transactions = 0
        for client in sample_transactions["Account_Id"].unique():
            client_rows = result[sample_transactions["Account_Id"] == client]
            first_idx = client_rows.index[0]  # after transform, index preserved? No, we need original order.
            # But the result is aligned with input order because we used _orig_idx.
            # However, we can filter by original index.
            first_amount = sample_transactions[sample_transactions["Account_Id"] == client].iloc[0]["TX_Amount"]
            # Find corresponding row in result where amount matches and transaction count is 0
            matching = result[(result["TX_Amount"] == first_amount) & (result["client_total_transactions"] == 0)]
            assert len(matching) >= 1

    def test_feature_values(self, sample_transactions):
        transformer = ClientBehaviorTransformer()
        transformer.fit(sample_transactions)
        result = transformer.transform(sample_transactions)
        # Check that avg amount is computed correctly for client C1 after first tx
        c1_rows = result.iloc[:5]
        # First row (index 0) should have avg = 0, dev = 0 (since cnt=0)
        assert c1_rows.iloc[0]["client_AVG_TX_Amount"] == 0
        assert c1_rows.iloc[0]["client_amount_deviation"] == 0
        # Second row: avg should be first amount = 100
        assert c1_rows.iloc[1]["client_AVG_TX_Amount"] == 100
        assert c1_rows.iloc[1]["client_amount_deviation"] == (150 - 100) / 100

    def test_z_score(self, sample_transactions):
        transformer = ClientBehaviorTransformer()
        transformer.fit(sample_transactions)
        result = transformer.transform(sample_transactions)
        # For client C3, amounts are [500,520,480,600] -> mean ~525, std ~51
        c3_rows = result.iloc[8:12]
        # First row: z_score should be 0 (since only one transaction)
        assert c3_rows.iloc[0]["client_amount_z_score"] == 0
        # Second row: z_score = (520 - mean_prev) / std_prev. mean_prev=500, std_prev=0? Actually std_prev undefined -> 0.
        # Implementation uses Welford; for count=1, std=0 => z_score=0.
        assert c3_rows.iloc[1]["client_amount_z_score"] == 0
        # Third row: count=2, mean=510, std=14.14 => (480-510)/14.14 ≈ -2.12
        # But we need to be approximate
        assert abs(c3_rows.iloc[2]["client_amount_z_score"] - (-2.12)) < 0.1

    def test_missing_columns(self):
        df = pd.DataFrame({"wrong_col": [1, 2]})
        transformer = ClientBehaviorTransformer()
        with pytest.raises(ValueError):
            transformer.fit(df)