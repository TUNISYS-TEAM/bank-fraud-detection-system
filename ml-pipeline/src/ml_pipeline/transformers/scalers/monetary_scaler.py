# src/ml_pipeline/transformers/scalers/monetary_scaler.py
"""
Safe scaler that scales only continuous numeric columns, leaving binary flags untouched.
"""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.preprocessing import MinMaxScaler, RobustScaler, StandardScaler
from sklearn.utils.validation import check_is_fitted

logger = logging.getLogger(__name__)

_SKIP_PATTERNS = ("is_", "_spike_", "fraud", "client_is_")
_MIN_UNIQUE = 10


class SafeScaler(BaseEstimator, TransformerMixin):
    """
    Scales only continuous numeric columns, skipping binary flags.

    Parameters
    ----------
    scaler_type : {'robust', 'standard', 'minmax'}
        'robust'   → RobustScaler  (recommended for fraud data with outliers)
        'standard' → StandardScaler
        'minmax'   → MinMaxScaler
    min_unique : int
        Columns with fewer distinct values are treated as binary/categorical
        and left unscaled.
    """

    def __init__(self, scaler_type: str = "robust", min_unique: int = _MIN_UNIQUE):
        self.scaler_type = scaler_type
        self.min_unique = min_unique

        self._scaler = _build_scaler(scaler_type)
        self._scale_cols: list[str] = []
        self.feature_names_in_: list[str] = []

    # ------------------------------------------------------------------
    def fit(self, X: pd.DataFrame, y=None) -> "SafeScaler":
        self.feature_names_in_ = list(X.columns)
        self._scale_cols = _identify_scale_cols(X, self.min_unique)

        if self._scale_cols:
            self._scaler.fit(X[self._scale_cols])
            logger.info(
                "SafeScaler.fit (%s) — scaling %d/%d columns",
                self.scaler_type, len(self._scale_cols), len(X.columns),
            )
        else:
            logger.warning("SafeScaler.fit — no continuous columns found to scale")

        return self

    # ------------------------------------------------------------------
    def transform(self, X: pd.DataFrame) -> pd.DataFrame:
        check_is_fitted(self, "_scale_cols")
        X = X.copy()

        cols_to_scale = []
        for col in self._scale_cols:
            if col not in X.columns:
                logger.warning(
                    "SafeScaler.transform: column '%s' missing — filling with 0", col
                )
                X[col] = np.float32(0)
            cols_to_scale.append(col)

        # Log new columns that weren't in fit (passthrough, no scaling)
        new_cols = set(X.columns) - set(self.feature_names_in_)
        if new_cols:
            logger.warning(
                "SafeScaler.transform: %d new columns not seen in fit "
                "(passing through): %s",
                len(new_cols), sorted(new_cols)[:5],
            )

        if cols_to_scale:
            X[cols_to_scale] = self._scaler.transform(
                X[cols_to_scale]
            ).astype(np.float32)

        return X

    # ------------------------------------------------------------------
    def get_feature_names_out(self, input_features=None) -> list[str]:
        check_is_fitted(self, "_scale_cols")
        return self.feature_names_in_

    def __repr__(self) -> str:
        return (
            f"SafeScaler(scaler_type='{self.scaler_type}', "
            f"min_unique={self.min_unique}, "
            f"n_scaled={len(getattr(self, '_scale_cols', []))})"
        )


# ---------------------------------------------------------------------------
# Private helpers
# ---------------------------------------------------------------------------
def _build_scaler(scaler_type: str):
    if scaler_type == "robust":
        return RobustScaler()
    if scaler_type == "minmax":
        return MinMaxScaler()
    if scaler_type == "standard":
        return StandardScaler()
    raise ValueError(
        f"Unknown scaler_type '{scaler_type}'. "
        f"Choose from: 'robust', 'standard', 'minmax'."
    )


def _identify_scale_cols(X: pd.DataFrame, min_unique: int) -> list[str]:
    cols = []
    for col in X.columns:
        if any(pat in col for pat in _SKIP_PATTERNS):
            continue
        if X[col].dtype not in (np.float32, np.float64, float):
            continue
        if X[col].nunique(dropna=True) < min_unique:
            continue
        cols.append(col)
    return cols