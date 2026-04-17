from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional

import lightgbm as lgb
import numpy as np
from sklearn.calibration import CalibratedClassifierCV

from shared_ml.models.base_model import FraudBaseModel

logger = logging.getLogger(__name__)


class LightGBMFraudModel(FraudBaseModel):
    """
    Classificateur de fraude LightGBM pour la production.

    Améliorations par rapport aux expérimentations en notebook :
    - scale_pos_weight calculé à partir des étiquettes (labels) d'entraînement
    - arrêt précoce (early stopping) surveillé sur l'AUC
    - calibration isotonique optionnelle sur l'ensemble de validation
    - importances des caractéristiques et booster exposés pour l'XAI / export
    - export ONNX supporté pour le modèle LightGBM de base
    """

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
        self.n_estimators = n_estimators
        self.learning_rate = learning_rate
        self.max_depth = max_depth
        self.num_leaves = num_leaves
        self.min_child_samples = min_child_samples
        self.subsample = subsample
        self.colsample_bytree = colsample_bytree
        self.reg_alpha = reg_alpha
        self.reg_lambda = reg_lambda
        self.early_stopping_rounds = early_stopping_rounds
        self.calibrate = calibrate
        self.threshold_metric = threshold_metric
        self.beta = beta

        self._model: Optional[lgb.LGBMClassifier] = None
        self._calibrated = None
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
            random_state=42,
            n_jobs=-1,
            verbose=-1,
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
                self._model,
                method="isotonic",
                cv="prefit",
            )
            self._calibrated.fit(X_val, y_val)
            logger.info("LightGBM: isotonic calibration fitted on validation set.")

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
            raise ValueError("LightGBMFraudModel must be fitted before predict_proba().")

        if self._calibrated is not None:
            return self._calibrated.predict_proba(X)[:, 1]

        return self._model.predict_proba(X)[:, 1]

    @property
    def feature_importances_(self):
        if self._model is None:
            raise ValueError("LightGBMFraudModel must be fitted before accessing feature_importances_.")
        return self._model.feature_importances_

    def get_booster(self):
        """Retourne le booster LightGBM sous-jacent pour SHAP ou les flux d'exportation."""
        if self._model is None:
            raise ValueError("LightGBMFraudModel must be fitted before get_booster().")
        return self._model.booster_

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """
        Exporte le modèle LightGBM au format ONNX.

        Si la calibration est activée, seul le modèle LightGBM de base est exporté.
        La calibration des probabilités doit alors être gérée séparément en aval.
        """
        if self._model is None:
            raise ValueError("LightGBMFraudModel must be fitted before to_onnx().")

        try:
            import onnxmltools
            from skl2onnx.common.data_types import FloatTensorType
        except ImportError as exc:
            raise ImportError(
                "Please install onnxmltools and skl2onnx to export ONNX models."
            ) from exc

        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        if feature_names:
            n_features = len(feature_names)
        elif self.feature_names_:
            n_features = len(self.feature_names_)
        else:
            n_features = self._model.n_features_in_

        initial_types = [("float_input", FloatTensorType([None, n_features]))]

        if self._calibrated is not None:
            logger.warning(
                "Calibrated model detected: exporting base LightGBM model only. "
                "Calibration must be handled separately."
            )

        onnx_model = onnxmltools.convert_lightgbm(
            self._model.booster_,
            initial_types=initial_types,
        )

        with open(output_path, "wb") as f:
            f.write(onnx_model.SerializeToString())

        logger.info("LightGBM model exported to ONNX: %s", output_path)

        meta = {
            "model_type": "lightgbm",
            "n_features": n_features,
            "feature_names": feature_names or self.feature_names_,
            "calibrated": self._calibrated is not None,
            "threshold": self.threshold_,
            "best_iteration": self.best_iteration_,
        }
        meta_path = output_path.with_suffix(".meta.json")
        meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")

        return output_path

    def get_feature_importance(self) -> dict[str, float]:
        """Retourne les importances des caractéristiques sous forme de mapping nom -> importance."""
        if self._model is None:
            return {}

        importances = self._model.feature_importances_
        if self.feature_names_ and len(self.feature_names_) == len(importances):
            return {
                name: float(value)
                for name, value in zip(self.feature_names_, importances, strict=False)
            }

        return {f"feature_{i}": float(v) for i, v in enumerate(importances)}