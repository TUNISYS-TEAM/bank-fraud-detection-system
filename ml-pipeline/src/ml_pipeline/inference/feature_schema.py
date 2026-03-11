from __future__ import annotations

from typing import Sequence

import numpy as np


# ---------------------------------------------------------------------------
# Raw input schema (before preprocessing)
# ---------------------------------------------------------------------------

RAW_INPUT_COLUMNS = [
    "Account_Id",
    "TX_Time",
    "TX_Amount",
    "Customer_Postal_Code",
    "Channel",
    "category",
]


# ---------------------------------------------------------------------------
# Intermediate / behavioral features (before final encoding)
# ---------------------------------------------------------------------------

BEHAVIORAL_FEATURES = [
    "TX_Amount",
    "client_AVG_TX_Amount",
    "client_AVG_TX_PerDay",
    "client_amount_deviation",
    "client_abs_amount_deviation",
    "client_amount_max_ratio",
    "client_amount_z_score",
    "client_is_new_max",
    "client_is_new_min",
    "client_total_transactions",
    "client_time_since_last_tx",
    "client_hours_since_last_tx",
    "client_total_days_since_first",
    "client_usual_region",
    "client_is_unusual_region",
    "client_region_freq",
    "client_usual_channel",
    "client_is_unusual_channel",
    "client_channel_freq",
    "client_usual_category",
    "client_is_unusual_category",
    "client_category_freq",
    "client_risk_score",
]


CATEGORICAL_COLUMNS = [
    "client_usual_region",
    "client_usual_channel",
    "client_usual_category",
]


# ---------------------------------------------------------------------------
# Final model feature schema (exact order expected by trained model)
# NOTE:
# This list must match the training-time preprocessing output exactly.
# Ideally it should come from a persisted artifact, not be hardcoded forever.
# ---------------------------------------------------------------------------

FINAL_FEATURE_NAMES = [
    "TX_Amount",
    "client_AVG_TX_Amount",
    "client_AVG_TX_PerDay",
    "client_amount_deviation",
    "client_abs_amount_deviation",
    "client_amount_max_ratio",
    "client_amount_z_score",
    "client_is_new_max",
    "client_is_new_min",
    "client_total_transactions",
    "client_time_since_last_tx",
    "client_hours_since_last_tx",
    "client_total_days_since_first",
    "client_usual_region__Ariana",
    "client_usual_region__Beja",
    "client_usual_region__Ben Arous",
    "client_usual_region__Bizerte",
    "client_usual_region__Gabes",
    "client_usual_region__Gafsa",
    "client_usual_region__Jendouba",
    "client_usual_region__Kairouan",
    "client_usual_region__Kasserine",
    "client_usual_region__Kebili",
    "client_usual_region__Manouba",
    "client_usual_region__Medenine",
    "client_usual_region__Monastir",
    "client_usual_region__Nabeul",
    "client_usual_region__Sfax",
    "client_usual_region__Sidi Bouzid",
    "client_usual_region__Siliana",
    "client_usual_region__Sousse",
    "client_usual_region__Tataouine",
    "client_usual_region__Tozeur",
    "client_usual_region__Tunis",
    "client_usual_region__Zaghouan",
    "client_usual_region__Unknown",
    "client_usual_channel__ATM",
    "client_usual_channel__Mobile",
    "client_usual_channel__Online",
    "client_usual_channel__POS",
    "client_usual_channel__Unknown",
    "client_usual_category__bills",
    "client_usual_category__entertainment",
    "client_usual_category__food",
    "client_usual_category__gambling",
    "client_usual_category__shopping",
    "client_usual_category__travel",
    "client_usual_category__Unknown",
    "client_region_freq",
    "client_channel_freq",
    "client_category_freq",
    "client_risk_score",
    "hour_sin",
    "hour_cos",
    "dow_sin",
    "dow_cos",
    "month_sin",
    "month_cos",
    "amount_log",
    "amount_hundreds",
    "amount_squared",
    "amount_abs_diff",
    "amount_ratio_raw",
    "is_amount_spike_2x",
    "is_amount_spike_3x",
    "is_amount_spike_5x",
    "is_amount_spike_10x",
    "is_weekend",
    "is_night",
    "is_business_hours",
    "is_dead_hours",
    "is_new_account_1d",
    "is_new_account_7d",
    "is_new_account_30d",
    "is_first_transaction",
    "is_second_transaction",
    "log_total_tx",
    "is_unusual_region_with_history",
    "is_unusual_channel_with_history",
    "is_unusual_category_with_history",
    "hours_since_last_tx_log",
    "is_long_inactive",
    "is_very_recent",
    "is_burst_1min",
    "is_burst_10min",
    "fraud_risk_score",
    "fraud_risk_score_log",
]


class FeatureSchema:
    """
    Utility class exposing the exact final feature order expected by the model.
    """

    @classmethod
    def get_raw_input_columns(cls) -> list[str]:
        return RAW_INPUT_COLUMNS.copy()

    @classmethod
    def get_behavioral_features(cls) -> list[str]:
        return BEHAVIORAL_FEATURES.copy()

    @classmethod
    def get_categorical_columns(cls) -> list[str]:
        return CATEGORICAL_COLUMNS.copy()

    @classmethod
    def get_final_feature_names(cls) -> list[str]:
        return FINAL_FEATURE_NAMES.copy()

    @classmethod
    def expected_feature_count(cls) -> int:
        return len(FINAL_FEATURE_NAMES)

    @classmethod
    def validate_input_array(cls, X: np.ndarray) -> bool:
        if X.ndim != 2:
            raise ValueError(f"Expected a 2D array, got {X.ndim}D array.")

        expected_dim = cls.expected_feature_count()
        if X.shape[1] != expected_dim:
            raise ValueError(
                f"Expected {expected_dim} features, got {X.shape[1]}. "
                f"Expected order: {FINAL_FEATURE_NAMES}"
            )

        if X.dtype not in (np.float32, np.float64):
            raise ValueError(
                f"Expected dtype float32 or float64, got {X.dtype}."
            )

        return True

    @classmethod
    def validate_feature_names(cls, feature_names: Sequence[str]) -> bool:
        expected = cls.get_final_feature_names()
        received = list(feature_names)

        if received != expected:
            raise ValueError(
                "Feature name/order mismatch between inference input and model schema."
            )

        return True