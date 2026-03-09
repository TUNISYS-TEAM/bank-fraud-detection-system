# src/ml_pipeline/transformers/behavioral/temporal_features.py
"""
Temporal feature transformer – creates datetime‑derived features and
composite fraud risk score.
"""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin

logger = logging.getLogger(__name__)


class TemporalFeatureTransformer(BaseEstimator, TransformerMixin):
    """
    Derives datetime and behavioural‑change features from the enriched
    DataFrame produced by ClientBehaviorTransformer.

    Parameters
    ----------
    amount_spike_thresholds : list[int]
        Multiples of the client average used for spike flags.
    risk_score_weights : dict | None
        Custom weights for the composite fraud_risk_score.
        If None, uses hardcoded production defaults.
    """

    _DEFAULT_WEIGHTS: dict[str, float] = {
        "is_amount_spike_3x": 2.0,
        "is_amount_spike_5x": 3.0,
        "is_amount_spike_10x": 5.0,
        "is_night": 1.0,
        "is_unusual_region_with_history": 4.0,
        "is_unusual_channel_with_history": 3.0,
        "is_unusual_category_with_history": 2.0,
        "is_new_account_7d": 3.0,
        "is_first_transaction": 5.0,
        # multiday-only (applied conditionally)
        "is_weekend": 0.5,
        "is_very_recent": 2.0,
    }

    _MULTIDAY_ONLY = {"is_weekend", "is_very_recent"}

    def __init__(
        self,
        amount_spike_thresholds: list[int] | None = None,
        risk_score_weights: dict | None = None,
    ):
        self.amount_spike_thresholds = amount_spike_thresholds or [2, 3, 5, 10]
        self.risk_score_weights = risk_score_weights or self._DEFAULT_WEIGHTS

        self.is_multiday_: bool = False
        self._feature_names_: list[str] = []

    # ------------------------------------------------------------------
    def fit(self, X: pd.DataFrame, y=None) -> "TemporalFeatureTransformer":
        _require_col(X, "TX_Time", "TemporalFeatureTransformer.fit")

        t = pd.to_datetime(X["TX_Time"], errors="coerce")
        self.is_multiday_ = t.dt.date.nunique() > 1

        logger.info(
            "TemporalFeatureTransformer.fit — %d rows, multiday=%s",
            len(X), self.is_multiday_,
        )
        # Dry-run on a 1-row slice to capture exact feature list
        sample = X.iloc[:1].copy()
        self._feature_names_ = list(self._transform_impl(sample).columns)
        return self

    # ------------------------------------------------------------------
    def transform(self, X: pd.DataFrame) -> pd.DataFrame:
        _require_col(X, "TX_Time", "TemporalFeatureTransformer.transform")
        logger.info("TemporalFeatureTransformer.transform — %d rows", len(X))
        return self._transform_impl(X.copy())

    # ------------------------------------------------------------------
    def _transform_impl(self, X: pd.DataFrame) -> pd.DataFrame:
        multiday = self.is_multiday_

        # ── 1. Amount transformations ──────────────────────────────────
        if "TX_Amount" in X.columns:
            X["amount_log"] = np.log1p(X["TX_Amount"]).astype(np.float32)
            X["amount_hundreds"] = (X["TX_Amount"] / 100.0).astype(np.float32)
            X["amount_squared"] = np.power(X["TX_Amount"], 2).astype(np.float32)

        # ── 2. Amount spike flags ──────────────────────────────────────
        if {"TX_Amount", "client_AVG_TX_Amount"}.issubset(X.columns):
            avg_safe = X["client_AVG_TX_Amount"].replace(0, 1.0)
            X["amount_abs_diff"] = (X["TX_Amount"] - X["client_AVG_TX_Amount"]).astype(np.float32)
            X["amount_ratio_raw"] = (X["TX_Amount"] / avg_safe).astype(np.float32)
            for thr in self.amount_spike_thresholds:
                X[f"is_amount_spike_{thr}x"] = (X["amount_ratio_raw"] > thr).astype(np.int8)

        # ── 3. Datetime extraction ─────────────────────────────────────
        if "TX_Time" in X.columns:
            t = pd.to_datetime(X["TX_Time"], errors="coerce")

            X["hour"] = t.dt.hour.fillna(0).astype(np.int8)
            X["dayofweek"] = t.dt.dayofweek.fillna(0).astype(np.int8)
            X["month"] = t.dt.month.fillna(1).astype(np.int8)
            X["quarter"] = t.dt.quarter.fillna(1).astype(np.int8)
            X["is_weekend"] = (t.dt.dayofweek >= 5).astype(np.int8)
            X["is_night"] = ((t.dt.hour >= 22) | (t.dt.hour <= 5)).astype(np.int8)

            # Cyclic encodings (float32)
            for col, period in [("hour", 24), ("dayofweek", 7)]:
                X[f"{col[:3] if col=='dayofweek' else col}_sin"] = (
                    np.sin(2 * np.pi * X[col] / period).astype(np.float32)
                )
                X[f"{col[:3] if col=='dayofweek' else col}_cos"] = (
                    np.cos(2 * np.pi * X[col] / period).astype(np.float32)
                )

            X.rename(columns={"dayofweek_sin": "dow_sin", "dayofweek_cos": "dow_cos"}, inplace=True)

            if multiday:
                X["is_business_hours"] = (
                    (X["hour"] >= 9) & (X["hour"] <= 17) & (X["dayofweek"] < 5)
                ).astype(np.int8)
                X["is_dead_hours"] = (
                    (X["hour"] >= 3) & (X["hour"] <= 5)
                ).astype(np.int8)
                X["month_sin"] = np.sin(2 * np.pi * X["month"] / 12).astype(np.float32)
                X["month_cos"] = np.cos(2 * np.pi * X["month"] / 12).astype(np.float32)

        # ── 4. Account-age flags ───────────────────────────────────────
        if "client_total_days_since_first" in X.columns:
            X["is_new_account_1d"] = (X["client_total_days_since_first"] < 1).astype(np.int8)
            X["is_new_account_7d"] = (X["client_total_days_since_first"] < 7).astype(np.int8)
            X["is_new_account_30d"] = (X["client_total_days_since_first"] < 30).astype(np.int8)

        if "client_total_transactions" in X.columns:
            X["is_first_transaction"] = (X["client_total_transactions"] == 0).astype(np.int8)
            X["is_second_transaction"] = (X["client_total_transactions"] == 1).astype(np.int8)
            X["log_total_tx"] = np.log1p(X["client_total_transactions"]).astype(np.float32)

        # ── 5. Behavioural anomaly flags with history guard ────────────
        for dim in ("region", "channel", "category"):
            unusual_col = f"client_is_unusual_{dim}"
            out_col = f"is_unusual_{dim}_with_history"
            if {unusual_col, "client_total_transactions"}.issubset(X.columns):
                X[out_col] = (
                    (X[unusual_col] == 1) & (X["client_total_transactions"] > 1)
                ).astype(np.int8)

        # ── 6. Inactivity / burst (multiday only) ─────────────────────
        if "client_hours_since_last_tx" in X.columns:
            if multiday:
                X["hours_since_last_tx_log"] = np.log1p(
                    X["client_hours_since_last_tx"]
                ).astype(np.float32)
                X["is_long_inactive"] = (X["client_hours_since_last_tx"] > 72).astype(np.int8)
                X["is_very_recent"] = (X["client_hours_since_last_tx"] < 1).astype(np.int8)
                if "client_time_since_last_tx" in X.columns:
                    gap = X["client_time_since_last_tx"]
                    X["is_burst_1min"] = (gap < 60).astype(np.int8)
                    X["is_burst_10min"] = (gap < 600).astype(np.int8)
            else:
                X["hours_since_last_tx_log"] = np.float32(0)
                X["is_long_inactive"] = np.int8(0)
                X["is_very_recent"] = np.int8(0)

        # ── 7. Composite risk score ────────────────────────────────────
        weights = self.risk_score_weights
        risk = np.zeros(len(X), dtype=np.float32)
        for col, w in weights.items():
            if col in self._MULTIDAY_ONLY and not multiday:
                continue
            if col in X.columns:
                risk += X[col].values.astype(np.float32) * np.float32(w)

        X["fraud_risk_score"] = risk
        X["fraud_risk_score_log"] = np.log1p(risk).astype(np.float32)

        return X

    # ------------------------------------------------------------------
    def get_feature_names_out(self, input_features=None) -> list[str]:
        return self._feature_names_


# ---------------------------------------------------------------------------
# Shared validation helper
# ---------------------------------------------------------------------------
def _require_col(df: pd.DataFrame, col: str, context: str):
    if col not in df.columns:
        raise ValueError(f"{context}: required column '{col}' not found in DataFrame")