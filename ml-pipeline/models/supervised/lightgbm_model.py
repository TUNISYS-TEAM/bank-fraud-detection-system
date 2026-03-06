"""
lightgbm_model.py — production LightGBM fraud classifier.

Fixes vs notebook
-----------------
- scale_pos_weight computed from y_train (not hardcoded to 8)
- Early stopping monitors AUC, not logloss
- Isotonic calibration optional (recommended before ensemble scoring)
- feature_importances_ and booster_ exposed for SHAP / XAI agent
- to_onnx() method for ONNX export
"""

from __future__ import annotations
import logging
from pathlib import Path
from typing import Optional
import numpy as np
from sklearn.calibration import CalibratedClassifierCV
import lightgbm as lgb
from models.base_model import FraudBaseModel

logger = logging.getLogger(__name__)


class LightGBMFraudModel(FraudBaseModel):

    def __init__(
        self,
        n_estimators: int = 1000,
        learning_rate: float = 0.02,
        max_depth: int = 8,
        num_leaves: int = 50,
        min_child_samples: int = 80,
        subsample: float = 0.8,
        colsample_bytree: float = 0.8,
        reg_alpha: float = 0.1,
        reg_lambda: float = 0.1,
        early_stopping_rounds: int = 30,
        calibrate: bool = True,
        threshold_metric: str = "fbeta",
        beta: float = 2.0,
    ):
        super().__init__("LightGBM")
        self.n_estimators           = n_estimators
        self.learning_rate          = learning_rate
        self.max_depth              = max_depth
        self.num_leaves             = num_leaves
        self.min_child_samples      = min_child_samples
        self.subsample              = subsample
        self.colsample_bytree       = colsample_bytree
        self.reg_alpha              = reg_alpha
        self.reg_lambda             = reg_lambda
        self.early_stopping_rounds  = early_stopping_rounds
        self.calibrate              = calibrate
        self.threshold_metric       = threshold_metric
        self.beta                   = beta
        self._model: Optional[lgb.LGBMClassifier] = None
        self._calibrated            = None
        self.feature_names_: list[str] = []
        self.best_iteration_: int = 0

    def fit(self, X, y, X_val=None, y_val=None):
        neg, pos = int((y == 0).sum()), int((y == 1).sum())
        spw = neg / pos if pos > 0 else 1.0
        logger.info("LightGBM scale_pos_weight=%.1f (neg=%d pos=%d)", spw, neg, pos)

        self._model = lgb.LGBMClassifier(
            n_estimators=self.n_estimators,
            learning_rate=self.learning_rate,
            max_depth=self.max_depth,
            num_leaves=self.num_leaves,
            min_child_samples=self.min_child_samples,
            subsample=self.subsample,
            colsample_bytree=self.colsample_bytree,
            reg_alpha=self.reg_alpha,
            reg_lambda=self.reg_lambda,
            scale_pos_weight=spw,
            random_state=42, n_jobs=-1, verbose=-1,
        )

        fit_kw = {}
        if X_val is not None and y_val is not None:
            fit_kw = dict(
                eval_set=[(X_val, y_val)],
                eval_metric="auc",
                callbacks=[
                    lgb.early_stopping(self.early_stopping_rounds, verbose=False),
                    lgb.log_evaluation(-1),
                ],
            )

        def _fit():
            self._model.fit(X, y, **fit_kw)
            self.best_iteration_ = getattr(self._model, "best_iteration_", 0)

        self._time_fit(_fit)

        if hasattr(X, "columns"):
            self.feature_names_ = list(X.columns)

        if self.calibrate and X_val is not None and y_val is not None:
            self._calibrated = CalibratedClassifierCV(
                self._model, method="isotonic", cv="prefit"
            )
            self._calibrated.fit(X_val, y_val)
            logger.info("LightGBM: isotonic calibration fitted on VAL")

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val), self._raw_proba(X_val),
                metric=self.threshold_metric, beta=self.beta,
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

    def get_booster(self):
        """Access underlying lgb.Booster for SHAP / ONNX."""
        return self._model.booster_

    # ------------------------------------------------------------------
    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """
        Export LightGBM model to ONNX format.
        
        Args:
            output_path: Where to save the ONNX file
            feature_names: Names of input features (for metadata)
        """
        try:
            import onnxmltools
            from skl2onnx.common.data_types import FloatTensorType
        except ImportError:
            raise ImportError("Please install onnxmltools and skl2onnx: pip install onnxmltools skl2onnx")
        
        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        
        # Determine number of features
        if feature_names:
            n_features = len(feature_names)
        elif hasattr(self, 'feature_names_') and self.feature_names_:
            n_features = len(self.feature_names_)
        else:
            # Try to get from model
            n_features = self._model.n_features_in_
        
        # Define input type
        initial_types = [('float_input', FloatTensorType([None, n_features]))]
        
        # Convert to ONNX
        if self._calibrated is not None:
            # If calibrated, we need to export the base model
            # Calibration will be handled in Java or as a separate step
            logger.warning("Calibrated model: exporting base LightGBM model. Calibration must be handled separately.")
            onnx_model = onnxmltools.convert_lightgbm(self._model.booster_, initial_types=initial_types)
        else:
            onnx_model = onnxmltools.convert_lightgbm(self._model.booster_, initial_types=initial_types)
        
        # Save ONNX model
        with open(output_path, "wb") as f:
            f.write(onnx_model.SerializeToString())
        
        logger.info(f"LightGBM model exported to ONNX: {output_path}")
        
        # Also save metadata
        meta = {
            "model_type": "lightgbm",
            "n_features": n_features,
            "feature_names": feature_names or self.feature_names_,
            "calibrated": self._calibrated is not None,
            "threshold": self.threshold_,
        }
        meta_path = output_path.with_suffix(".meta.json")
        meta_path.write_text(json.dumps(meta, indent=2))
        
        return output_path

    def get_feature_importance(self) -> dict:
        """Return feature importance dictionary."""
        if self._model is None:
            return {}
        
        importances = self._model.feature_importances_
        if self.feature_names_ and len(self.feature_names_) == len(importances):
            return dict(zip(self.feature_names_, importances.tolist()))
        return {f"feature_{i}": float(v) for i, v in enumerate(importances)}