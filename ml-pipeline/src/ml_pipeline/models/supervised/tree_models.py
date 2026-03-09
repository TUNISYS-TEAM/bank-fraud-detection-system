from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional

import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import RandomForestClassifier
import xgboost as xgb
from catboost import CatBoostClassifier

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

        if self.calibrate and X_val is not None and y_val is not None:
            self._calibrated = CalibratedClassifierCV(
                self._model, method="isotonic", cv="prefit"
            )
            self._calibrated.fit(X_val, y_val)

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val),
                self._raw_proba(X_val),
                metric=self.threshold_metric,
                beta=self.beta,
            )
        return self

    def predict_proba(self, X) -> np.ndarray:
        return self._raw_proba(X)

    def _raw_proba(self, X) -> np.ndarray:
        if self._calibrated is not None:
            return self._calibrated.predict_proba(X)[:, 1]
        return self._model.predict_proba(X)[:, 1]

    @property
    def feature_importances_(self):
        return self._model.feature_importances_

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """Export XGBoost model to ONNX format."""
        try:
            import onnxmltools
            from skl2onnx.common.data_types import FloatTensorType
        except ImportError:
            raise ImportError("Please install onnxmltools and skl2onnx")

        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        n_features = self._model.n_features_in_
        initial_types = [("float_input", FloatTensorType([None, n_features]))]

        if self._calibrated is not None:
            logger.warning(
                "Calibrated model: exporting base XGBoost. Calibration must be handled separately."
            )
            onnx_model = onnxmltools.convert_xgboost(
                self._model.get_booster(), initial_types=initial_types
            )
        else:
            onnx_model = onnxmltools.convert_xgboost(
                self._model.get_booster(), initial_types=initial_types
            )

        with open(output_path, "wb") as f:
            f.write(onnx_model.SerializeToString())

        meta = {
            "model_type": "xgboost",
            "n_features": n_features,
            "feature_names": feature_names or getattr(self, "feature_names_", []),
            "calibrated": self._calibrated is not None,
            "threshold": self.threshold_,
        }
        meta_path = output_path.with_suffix(".meta.json")
        meta_path.write_text(json.dumps(meta, indent=2))

        logger.info(f"XGBoost model exported to ONNX: {output_path}")
        return output_path


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

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val),
                self.predict_proba(X_val),
                metric=self.threshold_metric,
                beta=self.beta,
            )
        return self

    def predict_proba(self, X) -> np.ndarray:
        return self._model.predict_proba(X)[:, 1]

    @property
    def feature_importances_(self):
        return self._model.feature_importances_

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """Export Random Forest to ONNX."""
        try:
            import onnxmltools
            from skl2onnx.common.data_types import FloatTensorType
        except ImportError:
            raise ImportError("Please install onnxmltools and skl2onnx")

        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        n_features = self._model.n_features_in_
        initial_types = [("float_input", FloatTensorType([None, n_features]))]

        onnx_model = onnxmltools.convert_sklearn(self._model, initial_types=initial_types)

        with open(output_path, "wb") as f:
            f.write(onnx_model.SerializeToString())

        meta = {
            "model_type": "random_forest",
            "n_features": n_features,
            "feature_names": feature_names or getattr(self, "feature_names_", []),
            "threshold": self.threshold_,
        }
        meta_path = output_path.with_suffix(".meta.json")
        meta_path.write_text(json.dumps(meta, indent=2))

        logger.info(f"Random Forest model exported to ONNX: {output_path}")
        return output_path


class CatBoostFraudModel(FraudBaseModel):
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

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val),
                self.predict_proba(X_val),
                metric=self.threshold_metric,
                beta=self.beta,
            )
        return self

    def predict_proba(self, X) -> np.ndarray:
        return self._model.predict_proba(X)[:, 1]

    @property
    def feature_importances_(self):
        return self._model.get_feature_importance()

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """Export CatBoost model to ONNX (requires catboost>=1.0)."""
        try:
            from catboost.onnx import convert_catboost_to_onnx
        except ImportError:
            raise ImportError("Please install catboost with ONNX support (catboost>=1.0)")

        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        if feature_names is None:
            if hasattr(self, "feature_names_") and self.feature_names_:
                feature_names = self.feature_names_
            else:
                feature_names = [f"feature_{i}" for i in range(self._model.n_features_in_)]

        convert_catboost_to_onnx(
            model=self._model,
            output_file=str(output_path),
            feature_names=feature_names,
            verbose=False,
        )

        meta = {
            "model_type": "catboost",
            "n_features": self._model.n_features_in_,
            "feature_names": feature_names,
            "threshold": self.threshold_,
        }
        meta_path = output_path.with_suffix(".meta.json")
        meta_path.write_text(json.dumps(meta, indent=2))

        logger.info(f"CatBoost model exported to ONNX: {output_path}")
        return output_path