from __future__ import annotations

import logging

import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.utils.validation import check_is_fitted

logger = logging.getLogger(__name__)

_ALWAYS_DROP = frozenset({
    "TX_Time",
    "Account_Id",
    "_original_idx",
    "_orig_idx",
    "fraud",
    "Customer_Postal_Code", 
})

_RAW_CATS = frozenset({"region", "Channel", "category"})


class TreeFeatureSelector(BaseEstimator, TransformerMixin):
    """
    For LightGBM / XGBoost.
    Drops IDs, datetime, target and raw categorical columns
    (already one-hot encoded upstream).
    """

    def __init__(self, extra_drop: list[str] | None = None):
        self.extra_drop = extra_drop  # Garder l'original pour sklearn
        self.selected_features_: list[str] = []

    def fit(self, X: pd.DataFrame, y=None) -> "TreeFeatureSelector":
        # Calculer la liste au moment de l'utilisation
        extra_drop_list = self.extra_drop or []
        drop = _ALWAYS_DROP | _RAW_CATS | set(extra_drop_list)
        self.selected_features_ = [c for c in X.columns if c not in drop]
        logger.info(
            "TreeFeatureSelector.fit — %d/%d features selected",
            len(self.selected_features_),
            len(X.columns),
        )
        return self

    def transform(self, X: pd.DataFrame) -> pd.DataFrame:
        check_is_fitted(self, "selected_features_")
        available = [c for c in self.selected_features_ if c in X.columns]
        missing = set(self.selected_features_) - set(available)
        if missing:
            logger.warning(
                "TreeFeatureSelector.transform: %d features missing: %s",
                len(missing),
                sorted(missing)[:10],
            )
        return X[available]

    def get_feature_names_out(self, input_features=None) -> list[str]:
        check_is_fitted(self, "selected_features_")
        return self.selected_features_


class CatBoostFeatureSelector(BaseEstimator, TransformerMixin):
    """
    For CatBoost.
    Keeps raw categorical columns alongside numeric ones.
    Exposes categorical_feature_indices for CatBoost's Pool constructor.
    """

    def __init__(self, extra_drop: list[str] | None = None):
        self.extra_drop = extra_drop
        self.selected_features_: list[str] = []
        self.categorical_feature_indices_: list[int] = []

    def fit(self, X: pd.DataFrame, y=None) -> "CatBoostFeatureSelector":
        extra_drop_list = self.extra_drop or []
        drop = _ALWAYS_DROP | set(extra_drop_list)
        self.selected_features_ = [c for c in X.columns if c not in drop]

        self.categorical_feature_indices_ = [
            i for i, c in enumerate(self.selected_features_)
            if c in _RAW_CATS or "usual_" in c
        ]
        logger.info(
            "CatBoostFeatureSelector.fit — %d features, %d categorical",
            len(self.selected_features_),
            len(self.categorical_feature_indices_),
        )
        return self

    def transform(self, X: pd.DataFrame) -> pd.DataFrame:
        check_is_fitted(self, "selected_features_")
        available = [c for c in self.selected_features_ if c in X.columns]
        return X[available]

    def get_feature_names_out(self, input_features=None) -> list[str]:
        check_is_fitted(self, "selected_features_")
        return self.selected_features_


class DLFeatureSelector(BaseEstimator, TransformerMixin):
    """
    For Autoencoder / dense networks.
    Selects only numeric columns after encoding.
    Raises ValueError if any object column slips through.
    """

    def __init__(self, extra_drop: list[str] | None = None):
        self.extra_drop = extra_drop
        self.selected_features_: list[str] = []

    def fit(self, X: pd.DataFrame, y=None) -> "DLFeatureSelector":
        extra_drop_list = self.extra_drop or []
        drop = _ALWAYS_DROP | set(extra_drop_list)
        candidates = [c for c in X.columns if c not in drop]

        obj_cols = [c for c in candidates if X[c].dtype == object]
        if obj_cols:
            raise ValueError(
                "DLFeatureSelector.fit: object-dtype columns found after encoding — "
                f"check SafeOneHotEncoder upstream: {obj_cols[:10]}"
            )

        self.selected_features_ = candidates
        logger.info(
            "DLFeatureSelector.fit — %d numeric features selected",
            len(self.selected_features_),
        )
        return self

    def transform(self, X: pd.DataFrame) -> pd.DataFrame:
        check_is_fitted(self, "selected_features_")
        available = [c for c in self.selected_features_ if c in X.columns]
        result = X[available]

        obj_cols = [c for c in result.columns if result[c].dtype == object]
        if obj_cols:
            raise ValueError(
                f"DLFeatureSelector.transform: object columns in output: {obj_cols}"
            )
        return result

    def get_feature_names_out(self, input_features=None) -> list[str]:
        check_is_fitted(self, "selected_features_")
        return self.selected_features_