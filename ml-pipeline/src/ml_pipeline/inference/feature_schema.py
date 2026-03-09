"""
Defines the exact list and order of features expected by the trained models.
Used to validate input arrays before scoring and to document the interface.
"""

from typing import List, Optional

import numpy as np


class FeatureSchema:
    """
    Holds the ordered list of feature names expected by a model.
    Also provides validation methods for input arrays.
    """

    # This list must match the output of the preprocessing pipeline
    # (i.e., the final feature set after all transformations).
    # It is model‑specific; different model types may have different features.
    # For simplicity, we define one common schema for all tree‑based models.
    FEATURE_NAMES = [
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

    @classmethod
    def validate_input(cls, X: np.ndarray) -> bool:
        """
        Check that the input array has the expected shape and dtype.
        Returns True if valid, raises ValueError otherwise.
        """
        expected_dim = len(cls.FEATURE_NAMES)
        if X.ndim != 2:
            raise ValueError(f"Expected 2‑dimensional array, got {X.ndim}D")
        if X.shape[1] != expected_dim:
            raise ValueError(
                f"Expected {expected_dim} features, got {X.shape[1]}. "
                f"Feature names: {cls.FEATURE_NAMES}"
            )
        if X.dtype not in (np.float32, np.float64):
            raise ValueError(f"Expected float32 or float64, got {X.dtype}")
        return True

    @classmethod
    def get_feature_names(cls) -> List[str]:
        """Return the ordered list of feature names."""
        return cls.FEATURE_NAMES.copy()