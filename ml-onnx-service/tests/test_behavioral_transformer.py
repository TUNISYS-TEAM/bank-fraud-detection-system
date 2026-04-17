import pytest
import pandas as pd

from shared_ml.preprocessing.behavioral.client_behavior import ClientBehaviorTransformer


@pytest.fixture
def sample_transactions():
    """Create a small DataFrame with transactions for a few accounts."""
    data = {
        "Account_Id": ["A1"] * 5 + ["A2"] * 3 + ["A3"] * 4,
        "TX_Time": pd.to_datetime(
            [
                "2025-01-01 10:00", "2025-01-01 11:00", "2025-01-02 10:00", "2025-01-03 10:00", "2025-01-03 12:00",
                "2025-01-01 09:00", "2025-01-02 09:00", "2025-01-03 09:00",
                "2025-01-01 08:00", "2025-01-02 08:00", "2025-01-03 08:00", "2025-01-04 08:00",
            ]
        ),
        "TX_Amount": [100, 150, 200, 300, 50, 80, 120, 90, 500, 520, 480, 600],
        "Customer_Postal_Code": ["1000"] * 5 + ["2036"] * 3 + ["3000"] * 4,
        "Channel": ["ATM"] * 5 + ["Online"] * 3 + ["Mobile"] * 4,
        "category": ["shopping"] * 5 + ["food"] * 3 + ["bills"] * 4,
        "fraud": [0] * 11 + [1],
    }
    return pd.DataFrame(data)


class TestClientBehaviorTransformer:
    def test_fit_transform_shape(self, sample_transactions):
        transformer = ClientBehaviorTransformer(
            smoothing_days=1,
            default_time_gap_days=30,
            strict_as_of=True,
        )
        transformer.fit(sample_transactions)
        result = transformer.transform(sample_transactions)

        assert isinstance(result, pd.DataFrame)
        assert len(result) == len(sample_transactions)

        expected_cols = [
            "TX_Amount",
            "client_total_transactions",
            "client_time_since_last_tx",
            "client_hours_since_last_tx",
            "client_risk_score",
        ]
        assert all(col in result.columns for col in expected_cols)

    def test_first_transaction_has_zero_history(self, sample_transactions):
        transformer = ClientBehaviorTransformer(strict_as_of=True)
        transformer.fit(sample_transactions)
        result = transformer.transform(sample_transactions)

        for account_id in sample_transactions["Account_Id"].unique():
            first_idx = sample_transactions[sample_transactions["Account_Id"] == account_id].index[0]
            assert result.loc[first_idx, "client_total_transactions"] == 0

    def test_feature_values_basic(self, sample_transactions):
        transformer = ClientBehaviorTransformer(strict_as_of=True)
        transformer.fit(sample_transactions)
        result = transformer.transform(sample_transactions)

        c1_rows = result.iloc[:5]
        assert c1_rows.iloc[0]["client_total_transactions"] == 0
        assert c1_rows.iloc[0]["client_amount_deviation"] == 0
        assert c1_rows.iloc[1]["client_total_transactions"] == 1
        assert c1_rows.iloc[1]["client_AVG_TX_Amount"] == 100

    def test_missing_columns(self):
        df = pd.DataFrame({"wrong_col": [1, 2]})
        transformer = ClientBehaviorTransformer()

        with pytest.raises(ValueError):
            transformer.fit(df)