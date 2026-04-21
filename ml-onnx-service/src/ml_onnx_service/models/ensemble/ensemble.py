from __future__ import annotations

import logging
from pathlib import Path
from typing import Optional

import numpy as np
from scipy.optimize import minimize
from sklearn.metrics import fbeta_score

from shared_ml.models.base_model import FraudBaseModel

logger = logging.getLogger(__name__)


class WeightedEnsemble(FraudBaseModel):
    """
    Moyenne pondérée des instances FraudBaseModel ajustées.

    Tous les modèles doivent exposer predict_proba() retournant des scores calibrés
    ou normalisés par rang sur une échelle comparable [0, 1].
    """

    def __init__(
        self,
        models: list[FraudBaseModel],
        beta: float = 2.0,
        n_weight_restarts: int = 10,
    ):
        super().__init__("WeightedEnsemble")
        if not models:
            raise ValueError("WeightedEnsemble requires at least one base model.")

        self.models = models
        self.beta = beta
        self.n_weight_restarts = n_weight_restarts
        self.weights_: Optional[np.ndarray] = None

    def fit(self, X, y, X_val=None, y_val=None):
        """Optimise les poids de l'ensemble sur les données de validation en utilisant F-beta."""
        if X_val is None or y_val is None:
            raise ValueError("WeightedEnsemble.fit requires X_val and y_val.")

        y_val = np.asarray(y_val)
        n = len(self.models)
        scores_val = np.column_stack([m.predict_proba(X_val) for m in self.models])

        def _neg_fbeta(raw_w):
            w = np.exp(raw_w - raw_w.max())
            w /= w.sum()

            composite = scores_val @ w
            lo, hi = np.percentile(composite, [1, 99])

            best = -1.0
            for t in np.linspace(lo, hi, 200):
                yp = (composite > t).astype(int)
                if yp.sum() == 0:
                    continue
                score = fbeta_score(y_val, yp, beta=self.beta, zero_division=0)
                if score > best:
                    best = score

            return -best

        best_result = None
        rng = np.random.default_rng(42)

        for _ in range(self.n_weight_restarts):
            x0 = rng.normal(0, 1, n)
            result = minimize(
                _neg_fbeta,
                x0,
                method="Nelder-Mead",
                options={"maxiter": 2000, "xatol": 1e-4},
            )
            if best_result is None or result.fun < best_result.fun:
                best_result = result

        raw_w = best_result.x
        w = np.exp(raw_w - raw_w.max())
        w /= w.sum()
        self.weights_ = w

        logger.info(
            "WeightedEnsemble weights: %s",
            {m.name: f"{wi:.3f}" for m, wi in zip(self.models, w)},
        )

        composite_val = scores_val @ self.weights_
        self.find_best_threshold(y_val, composite_val, metric="fbeta", beta=self.beta)

        self._fitted = True
        return self

    def predict_proba(self, X) -> np.ndarray:
        if self.weights_ is None:
            raise ValueError("WeightedEnsemble must be fitted before predict_proba().")

        scores = np.column_stack([m.predict_proba(X) for m in self.models])
        return scores @ self.weights_

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        raise NotImplementedError("Ensemble ONNX export not supported.")


class ANDEnsemble(FraudBaseModel):
    """
    Signale une transaction uniquement lorsque tous les modèles sont d'accord.
    Maximise la précision au détriment du rappel.
    """

    def __init__(self, models: list[FraudBaseModel], beta: float = 2.0):
        super().__init__("ANDEnsemble")
        if not models:
            raise ValueError("ANDEnsemble requires at least one base model.")

        self.models = models
        self.beta = beta

    def fit(self, X, y, X_val=None, y_val=None):
        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val),
                self.predict_proba(X_val),
                metric="fbeta",
                beta=self.beta,
            )
        self._fitted = True
        return self

    def predict_proba(self, X) -> np.ndarray:
        scores = np.column_stack([m.predict_proba(X) for m in self.models])
        log_scores = np.log(np.clip(scores, 1e-9, 1.0))
        return np.exp(log_scores.mean(axis=1))

    def predict(self, X) -> np.ndarray:
        preds = np.column_stack([m.predict(X) for m in self.models])
        return preds.all(axis=1).astype(int)

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        raise NotImplementedError("ANDEnsemble ONNX export not supported.")


class ORBoostEnsemble(FraudBaseModel):
    """
    Signale lorsqu'un modèle est très confiant, ou lorsque le score composite boosté
    dépasse le seuil appris.
    """

    def __init__(
        self,
        models: list[FraudBaseModel],
        high_conf_threshold: float = 0.85,
        beta: float = 2.0,
    ):
        super().__init__("ORBoostEnsemble")
        if not models:
            raise ValueError("ORBoostEnsemble requires at least one base model.")

        self.models = models
        self.high_conf_threshold = high_conf_threshold
        self.beta = beta

    def fit(self, X, y, X_val=None, y_val=None):
        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val),
                self.predict_proba(X_val),
                metric="fbeta",
                beta=self.beta,
            )
        self._fitted = True
        return self

    def predict_proba(self, X) -> np.ndarray:
        scores = np.column_stack([m.predict_proba(X) for m in self.models])
        avg = scores.mean(axis=1)
        max_s = scores.max(axis=1)
        return np.where(max_s > self.high_conf_threshold, max_s, avg)

    def predict(self, X) -> np.ndarray:
        scores = np.column_stack([m.predict_proba(X) for m in self.models])
        any_high = (scores > self.high_conf_threshold).any(axis=1)
        composite_alert = self.predict_proba(X) > self.threshold_
        return (any_high | composite_alert).astype(int)

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        raise NotImplementedError("ORBoostEnsemble ONNX export not supported.")


def compare_models(
    models: list[FraudBaseModel],
    y_true: np.ndarray,
    X,
    sort_by: str = "f2",
) -> "pd.DataFrame":
    """
    Évalue tous les modèles et retourne un DataFrame de comparaison trié.
    """
    import pandas as pd

    rows = [m.evaluate(y_true, X) for m in models]
    df = pd.DataFrame(rows)

    for col in ["roc_auc", "avg_precision", "f1", "f2", "precision", "recall"]:
        if col in df.columns:
            df[col] = df[col].round(4)

    return df.sort_values(sort_by, ascending=False).reset_index(drop=True)