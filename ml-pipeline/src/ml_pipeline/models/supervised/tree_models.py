from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional

import numpy as np
import xgboost as xgb
from catboost import CatBoostClassifier
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import RandomForestClassifier

from ml_pipeline.models.base_model import FraudBaseModel

logger = logging.getLogger(__name__)


class XGBoostFraudModel(FraudBaseModel):
    def __init__(
        self,
        n_estimators: int = 500,
        max_depth: int = 8,
        learning_rate: float = 0.02,
        subsample: float = 0.8,
        colsample_bytree: float = 0.8,
        early_stopping_rounds: int = 30,
        calibrate: bool = True,
        threshold_metric: str = "fbeta",
        beta: float = 2.0,
    ):
        super().__init__("XGBoost")
        self.n_estimators = n_estimators
        self.max_depth = max_depth
        self.learning_rate = learning_rate
        self.subsample = subsample
        self.colsample_bytree = colsample_bytree
        self.early_stopping_rounds = early_stopping_rounds
        self.calibrate = calibrate
        self.threshold_metric = threshold_metric
        self.beta = beta

        self._model = None
        self._calibrated = None
        self.feature_names_: list[str] = []

    def fit(self, X, y, X_val=None, y_val=None):
        neg, pos = int((y == 0).sum()), int((y == 1).sum())
        spw = neg / pos if pos > 0 else 1.0

        self._model = xgb.XGBClassifier(
            n_estimators=self.n_estimators,
            max_depth=self.max_depth,
            learning_rate=self.learning_rate,
            subsample=self.subsample,
            colsample_bytree=self.colsample_bytree,
            scale_pos_weight=spw,
            early_stopping_rounds=self.early_stopping_rounds if X_val is not None else None,
            eval_metric="aucpr",
            random_state=42,
            n_jobs=-1,
            verbosity=0,
            use_label_encoder=False,
        )

        fit_kw = {}
        if X_val is not None and y_val is not None:
            fit_kw = dict(eval_set=[(X_val, y_val)], verbose=False)

        self._time_fit(self._model.fit, X, y, **fit_kw)

        if hasattr(X, "columns"):
            self.feature_names_ = list(X.columns)

        if self.calibrate and X_val is not None and y_val is not None:
            self._calibrated = CalibratedClassifierCV(
                self._model,
                method="isotonic",
                cv="prefit",
            )
            self._calibrated.fit(X_val, y_val)

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val),
                self._raw_proba(X_val),
                metric=self.threshold_metric,
                beta=self.beta,
            )

        self._fitted = True
        return self

    def predict_proba(self, X) -> np.ndarray:
        return self._raw_proba(X)

    def _raw_proba(self, X) -> np.ndarray:
        if self._model is None:
            raise ValueError("XGBoostFraudModel must be fitted before predict_proba().")

        if self._calibrated is not None:
            return self._calibrated.predict_proba(X)[:, 1]

        return self._model.predict_proba(X)[:, 1]

    @property
    def feature_importances_(self):
        if self._model is None:
            raise ValueError("XGBoostFraudModel must be fitted before accessing feature_importances_.")
        return self._model.feature_importances_

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """Export XGBoost model to ONNX format."""
        if self._model is None:
            raise ValueError("XGBoostFraudModel must be fitted before to_onnx().")

        try:
            import onnxmltools
            from skl2onnx.common.data_types import FloatTensorType
        except ImportError as exc:
            raise ImportError("Please install onnxmltools and skl2onnx.") from exc

        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        names = feature_names or self.feature_names_
        n_features = len(names) if names else self._model.n_features_in_
        initial_types = [("float_input", FloatTensorType([None, n_features]))]

        if self._calibrated is not None:
            logger.warning("Calibrated model detected: exporting base XGBoost model only.")

        onnx_model = onnxmltools.convert_xgboost(
            self._model.get_booster(),
            initial_types=initial_types,
        )

        with open(output_path, "wb") as f:
            f.write(onnx_model.SerializeToString())

        logger.info("XGBoost model exported to ONNX: %s", output_path)

        meta = {
            "model_type": "xgboost",
            "n_features": n_features,
            "feature_names": names,
            "calibrated": self._calibrated is not None,
            "threshold": self.threshold_,
        }
        output_path.with_suffix(".meta.json").write_text(
            json.dumps(meta, indent=2),
            encoding="utf-8",
        )
        return output_path

    def get_feature_importance(self) -> dict[str, float]:
        if self._model is None:
            return {}

        importances = self._model.feature_importances_
        if self.feature_names_ and len(self.feature_names_) == len(importances):
            return {
                name: float(value)
                for name, value in zip(self.feature_names_, importances, strict=False)
            }

        return {f"feature_{i}": float(v) for i, v in enumerate(importances)}


class RandomForestFraudModel(FraudBaseModel):
    def __init__(
        self,
        n_estimators: int = 200,
        max_depth: int = 15,
        min_samples_leaf: int = 10,
        threshold_metric: str = "fbeta",
        beta: float = 2.0,
    ):
        super().__init__("RandomForest")
        self.n_estimators = n_estimators
        self.max_depth = max_depth
        self.min_samples_leaf = min_samples_leaf
        self.threshold_metric = threshold_metric
        self.beta = beta

        self._model = None
        self.feature_names_: list[str] = []

    def fit(self, X, y, X_val=None, y_val=None):
        self._model = RandomForestClassifier(
            n_estimators=self.n_estimators,
            max_depth=self.max_depth,
            min_samples_leaf=self.min_samples_leaf,
            class_weight="balanced",
            random_state=42,
            n_jobs=-1,
        )
        self._time_fit(self._model.fit, X, y)

        if hasattr(X, "columns"):
            self.feature_names_ = list(X.columns)

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val),
                self.predict_proba(X_val),
                metric=self.threshold_metric,
                beta=self.beta,
            )

        self._fitted = True
        return self

    def predict_proba(self, X) -> np.ndarray:
        if self._model is None:
            raise ValueError("RandomForestFraudModel must be fitted before predict_proba().")
        return self._model.predict_proba(X)[:, 1]

    @property
    def feature_importances_(self):
        if self._model is None:
            raise ValueError("RandomForestFraudModel must be fitted before accessing feature_importances_.")
        return self._model.feature_importances_

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """Export RandomForest model to ONNX format."""
        if self._model is None:
            raise ValueError("RandomForestFraudModel must be fitted before to_onnx().")

        try:
            from skl2onnx import convert_sklearn
            from skl2onnx.common.data_types import FloatTensorType
        except ImportError as exc:
            raise ImportError("Please install skl2onnx.") from exc

        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        names = feature_names or self.feature_names_
        n_features = len(names) if names else self._model.n_features_in_
        initial_types = [("float_input", FloatTensorType([None, n_features]))]

        onnx_model = convert_sklearn(self._model, initial_types=initial_types)

        with open(output_path, "wb") as f:
            f.write(onnx_model.SerializeToString())

        logger.info("RandomForest model exported to ONNX: %s", output_path)

        meta = {
            "model_type": "random_forest",
            "n_features": n_features,
            "feature_names": names,
            "threshold": self.threshold_,
        }
        output_path.with_suffix(".meta.json").write_text(
            json.dumps(meta, indent=2),
            encoding="utf-8",
        )
        return output_path

    def get_feature_importance(self) -> dict[str, float]:
        if self._model is None:
            return {}

        importances = self._model.feature_importances_
        if self.feature_names_ and len(self.feature_names_) == len(importances):
            return {
                name: float(value)
                for name, value in zip(self.feature_names_, importances, strict=False)
            }

        return {f"feature_{i}": float(v) for i, v in enumerate(importances)}


class CatBoostFraudModel(FraudBaseModel):
    """
    CatBoost fraud model with native categorical support.
    """

    def __init__(
        self,
        iterations: int = 500,
        depth: int = 6,
        learning_rate: float = 0.05,
        l2_leaf_reg: float = 3.0,
        early_stopping_rounds: int = 30,
        cat_features: Optional[list] = None,
        threshold_metric: str = "fbeta",
        beta: float = 2.0,
    ):
        super().__init__("CatBoost")
        self.iterations = iterations
        self.depth = depth
        self.learning_rate = learning_rate
        self.l2_leaf_reg = l2_leaf_reg
        self.early_stopping_rounds = early_stopping_rounds
        self.cat_features = cat_features
        self.threshold_metric = threshold_metric
        self.beta = beta

        self._model = None
        self.feature_names_: list[str] = []

    def fit(self, X, y, X_val=None, y_val=None):
        neg, pos = int((y == 0).sum()), int((y == 1).sum())
        class_weights = [1.0, neg / pos] if pos > 0 else [1.0, 1.0]

        self._model = CatBoostClassifier(
            iterations=self.iterations,
            depth=self.depth,
            learning_rate=self.learning_rate,
            l2_leaf_reg=self.l2_leaf_reg,
            early_stopping_rounds=self.early_stopping_rounds if X_val is not None else None,
            class_weights=class_weights,
            cat_features=self.cat_features,
            random_seed=42,
            verbose=False,
        )

        fit_kw = {}
        if X_val is not None and y_val is not None:
            fit_kw = dict(eval_set=(X_val, y_val), verbose=False)

        self._time_fit(self._model.fit, X, y, **fit_kw)

        if hasattr(X, "columns"):
            self.feature_names_ = list(X.columns)

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val),
                self.predict_proba(X_val),
                metric=self.threshold_metric,
                beta=self.beta,
            )

        self._fitted = True
        return self

    def predict_proba(self, X) -> np.ndarray:
        if self._model is None:
            raise ValueError("CatBoostFraudModel must be fitted before predict_proba().")
        return self._model.predict_proba(X)[:, 1]

    @property
    def feature_importances_(self):
        if self._model is None:
            raise ValueError("CatBoostFraudModel must be fitted before accessing feature_importances_.")
        return self._model.get_feature_importance()

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """Export CatBoost model to ONNX using native CatBoost export."""
        if self._model is None:
            raise ValueError("CatBoostFraudModel must be fitted before to_onnx().")

        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        names = feature_names or self.feature_names_
        if not names:
            names = list(getattr(self._model, "feature_names_", []))

        n_features = len(names) if names else getattr(self._model, "n_features_in_", 0)

        self._model.save_model(
            str(output_path),
            format="onnx",
            export_parameters={
                "onnx_domain": "ai.catboost",
                "feature_names": names or [f"f{i}" for i in range(n_features)],
            },
        )

        logger.info("CatBoost model exported to ONNX: %s", output_path)

        meta = {
            "model_type": "catboost",
            "n_features": n_features,
            "feature_names": names,
            "threshold": self.threshold_,
        }
        output_path.with_suffix(".meta.json").write_text(
            json.dumps(meta, indent=2),
            encoding="utf-8",
        )
        return output_path

    def get_feature_importance(self) -> dict[str, float]:
        if self._model is None:
            return {}

        importances = self._model.get_feature_importance()
        if self.feature_names_ and len(self.feature_names_) == len(importances):
            return {
                name: float(value)
                for name, value in zip(self.feature_names_, importances, strict=False)
            }

        return {f"feature_{i}": float(v) for i, v in enumerate(importances)}