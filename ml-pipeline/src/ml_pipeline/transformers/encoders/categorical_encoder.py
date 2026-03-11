from __future__ import annotations

import logging

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.preprocessing import OneHotEncoder
from sklearn.utils.validation import check_is_fitted

logger = logging.getLogger(__name__)

_DEFAULT_CATEGORICAL_COLS = [
    "client_usual_region",
    "client_usual_channel",
    "client_usual_category",
]

_RAW_CATEGORICAL_COLS = ["region", "Channel", "category"]


class SafeOneHotEncoder(BaseEstimator, TransformerMixin):
    """
    Encode par One-hot une liste fixe de colonnes catégorielles.

    Paramètres
    ----------
    categorical_cols : list[str]
        Colonnes à encoder. Toutes les autres sont transmises sans modification.
    include_raw_cats : bool
        Si True, encode également les colonnes brutes region / Channel / category
        (utile si vous souhaitez à la fois les colonnes brutes et les colonnes usual_* encodées).
    """

    def __init__(
        self,
        categorical_cols: list[str] | None = None,
        include_raw_cats: bool = False,
    ):
        self.categorical_cols = categorical_cols or _DEFAULT_CATEGORICAL_COLS
        self.include_raw_cats = include_raw_cats

        self._encoders: dict[str, OneHotEncoder] = {}
        self._encoded_names: list[str] = []
        self.feature_names_in_: list[str] = []

    def fit(self, X: pd.DataFrame, y=None) -> "SafeOneHotEncoder":
        cols = list(self.categorical_cols)
        if self.include_raw_cats:
            cols += [c for c in _RAW_CATEGORICAL_COLS if c not in cols]

        self._encoders = {}
        self._encoded_names = []
        self.feature_names_in_ = list(X.columns)

        for col in cols:
            if col not in X.columns:
                logger.warning("SafeOneHotEncoder.fit: column '%s' not found — skipped", col)
                continue

            values = X[[col]].fillna("Unknown")
            enc = OneHotEncoder(
                sparse_output=False,
                handle_unknown="ignore",
                dtype=np.float32,
            )
            enc.fit(values)
            self._encoders[col] = enc
            self._encoded_names.extend(
                [f"{col}__{cat}" for cat in enc.categories_[0]]
            )

        logger.info(
            "SafeOneHotEncoder.fit — %d columns encoded → %d OHE features",
            len(self._encoders),
            len(self._encoded_names),
        )
        return self

    def transform(self, X: pd.DataFrame) -> pd.DataFrame:
        check_is_fitted(self, "_encoders")

        X = X.copy()
        non_cat = [c for c in X.columns if c not in self._encoders]
        result = X[non_cat].copy()

        for col, enc in self._encoders.items():
            feat_names = [f"{col}__{cat}" for cat in enc.categories_[0]]

            if col not in X.columns:
                logger.warning(
                    "SafeOneHotEncoder.transform: column '%s' missing — filling zeros", col
                )
                zeros = pd.DataFrame(
                    np.zeros((len(X), len(feat_names)), dtype=np.float32),
                    columns=feat_names,
                    index=X.index,
                )
                result = pd.concat([result, zeros], axis=1)
                continue

            values = X[[col]].fillna("Unknown")

            known = set(enc.categories_[0])
            seen = set(values[col].unique())
            unknowns = seen - known
            if unknowns:
                logger.warning(
                    "SafeOneHotEncoder.transform: column '%s' has %d unknown "
                    "categories not seen in fit: %s",
                    col,
                    len(unknowns),
                    list(unknowns)[:5],
                )

            encoded = enc.transform(values)
            enc_df = pd.DataFrame(encoded, columns=feat_names, index=X.index)
            result = pd.concat([result, enc_df], axis=1)

        logger.debug("SafeOneHotEncoder.transform — output shape %s", result.shape)
        return result

    def get_feature_names_out(self, input_features=None) -> list[str]:
        check_is_fitted(self, "_encoders")
        non_cat = [c for c in self.feature_names_in_ if c not in self._encoders]
        return non_cat + self._encoded_names