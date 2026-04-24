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
_MIN_SCALE_SPREAD = 1e-3


class SafeScaler(BaseEstimator, TransformerMixin):
    """
    Met à l'échelle uniquement les colonnes numériques continues, en ignorant les indicateurs binaires.

    Protections ajoutées :
    - exclusion des colonnes quasi constantes / quasi non dispersées
    - nettoyage inf / -inf / NaN avant fit/transform
    - logs explicites des colonnes ignorées
    """

    def __init__(
        self,
        scaler_type: str = "robust",
        min_unique: int = _MIN_UNIQUE,
        min_scale_spread: float = _MIN_SCALE_SPREAD,
    ):
        self.scaler_type = scaler_type
        self.min_unique = min_unique
        self.min_scale_spread = min_scale_spread

        self._scaler = _build_scaler(scaler_type)
        self._scale_cols: list[str] = []
        self._skipped_low_spread_: dict[str, float] = {}
        self.feature_names_in_: list[str] = []

    def fit(self, X: pd.DataFrame, y=None) -> "SafeScaler":
        X = X.copy()
        self.feature_names_in_ = list(X.columns)

        X = _sanitize_numeric_frame(X)

        candidate_cols = _identify_scale_cols(X, self.min_unique)

        self._scale_cols = []
        self._skipped_low_spread_ = {}

        for col in candidate_cols:
            spread = _compute_spread(X[col], self.scaler_type)
            if not np.isfinite(spread) or spread < self.min_scale_spread:
                self._skipped_low_spread_[col] = float(spread) if np.isfinite(spread) else float("nan")
                continue
            self._scale_cols.append(col)

        if self._scale_cols:
            self._scaler.fit(X[self._scale_cols])
            logger.info(
                "SafeScaler.fit (%s) — scaling %d/%d columns",
                self.scaler_type,
                len(self._scale_cols),
                len(X.columns),
            )
        else:
            logger.warning("SafeScaler.fit — no continuous columns found to scale")

        if self._skipped_low_spread_:
            logger.warning(
                "SafeScaler.fit — skipped %d low-spread columns: %s",
                len(self._skipped_low_spread_),
                {
                    k: round(v, 8) if np.isfinite(v) else v
                    for k, v in list(self._skipped_low_spread_.items())[:15]
                },
            )

        return self

    def transform(self, X: pd.DataFrame) -> pd.DataFrame:
        check_is_fitted(self, "_scale_cols")
        X = X.copy()
        X = _sanitize_numeric_frame(X)

        cols_to_scale = []
        for col in self._scale_cols:
            if col not in X.columns:
                logger.warning(
                    "SafeScaler.transform: column '%s' missing — filling with 0", col
                )
                X[col] = np.float32(0)
            cols_to_scale.append(col)

        new_cols = set(X.columns) - set(self.feature_names_in_)
        if new_cols:
            logger.warning(
                "SafeScaler.transform: %d new columns not seen in fit "
                "(passing through): %s",
                len(new_cols),
                sorted(new_cols)[:5],
            )

        if cols_to_scale:
            scaled = self._scaler.transform(X[cols_to_scale])
            scaled = np.nan_to_num(scaled, nan=0.0, posinf=20.0, neginf=-20.0).astype(np.float32)
            scaled = np.clip(scaled, -20.0, 20.0)
            X[cols_to_scale] = scaled

        return X

    def get_feature_names_out(self, input_features=None) -> list[str]:
        check_is_fitted(self, "_scale_cols")
        return self.feature_names_in_

    def __repr__(self) -> str:
        return (
            f"SafeScaler(scaler_type='{self.scaler_type}', "
            f"min_unique={self.min_unique}, "
            f"min_scale_spread={self.min_scale_spread}, "
            f"n_scaled={len(getattr(self, '_scale_cols', []))})"
        )


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


def _sanitize_numeric_frame(X: pd.DataFrame) -> pd.DataFrame:
    X = X.copy()
    numeric_cols = X.select_dtypes(include=[np.number]).columns
    if len(numeric_cols) > 0:
        X[numeric_cols] = X[numeric_cols].replace([np.inf, -np.inf], np.nan)
        X[numeric_cols] = X[numeric_cols].fillna(0.0)
    return X


def _compute_spread(series: pd.Series, scaler_type: str) -> float:
    s = pd.to_numeric(series, errors="coerce").replace([np.inf, -np.inf], np.nan).dropna()
    if s.empty:
        return 0.0

    if scaler_type == "robust":
        q75 = s.quantile(0.75)
        q25 = s.quantile(0.25)
        return float(q75 - q25)

    if scaler_type == "standard":
        return float(s.std(ddof=0))

    if scaler_type == "minmax":
        return float(s.max() - s.min())

    return 0.0


def _identify_scale_cols(X: pd.DataFrame, min_unique: int) -> list[str]:
    cols = []
    for col in X.columns:
        if any(pat in col for pat in _SKIP_PATTERNS):
            continue
        if not pd.api.types.is_numeric_dtype(X[col]):
            continue
        if X[col].nunique(dropna=True) < min_unique:
            continue
        cols.append(col)
    return cols