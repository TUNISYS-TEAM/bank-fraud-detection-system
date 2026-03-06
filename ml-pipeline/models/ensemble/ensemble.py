"""
ensemble.py — Calibrated ensemble for fraud detection.

Fixes vs notebook PolybrideFraudDetector
-----------------------------------------
1. Score combination uses rank-normalized [0,1] scores from each model's
   RankNormalizer. This makes weighted averaging mathematically valid because
   all inputs are on the same scale (percentile of anomaly on TRAIN).
   The notebook's geometric mean of a calibrated probability and a batch
   min-max normalized ECOD score was mixing incompatible distributions.

2. Weight optimization uses scipy.optimize.minimize (Nelder-Mead) over a
   continuous weight space, not 6 hardcoded combinations.
   Weights are constrained to sum to 1 via softmax parameterization.

3. Three ensemble strategies:
   - WeightedEnsemble     : learned weights, single threshold (recommended)
   - ANDEnsemble          : flag only when ALL models agree (high precision)
   - ORBoostEnsemble      : flag when any model is very confident (high recall)

4. train_time tracked so comparison table never throws KeyError.
"""

from __future__ import annotations
import logging
from typing import Optional
import numpy as np
from scipy.optimize import minimize
from sklearn.metrics import fbeta_score
from models.base_model import FraudBaseModel, _safe
from sklearn.metrics import roc_auc_score, average_precision_score, f1_score, precision_score, recall_score

logger = logging.getLogger(__name__)


class WeightedEnsemble(FraudBaseModel):
    """
    Weighted average of N fitted FraudBaseModel instances.

    All models must expose predict_proba() returning rank-normalized [0,1]
    scores (i.e. built on RankNormalizer — LightGBM uses isotonic calibration
    which also gives stable [0,1] output).

    Parameters
    ----------
    models      : list of fitted FraudBaseModel instances
    beta        : Fbeta weight for threshold search (default 2.0 = favour recall)
    n_weight_restarts : random restarts for weight optimization
    """

    def __init__(
        self,
        models: list[FraudBaseModel],
        beta: float = 2.0,
        n_weight_restarts: int = 10,
    ):
        super().__init__("WeightedEnsemble")
        self.models             = models
        self.beta               = beta
        self.n_weight_restarts  = n_weight_restarts
        self.weights_: Optional[np.ndarray] = None

    def fit(self, X, y, X_val=None, y_val=None):
        """Optimize weights on VAL using Fbeta objective."""
        if X_val is None or y_val is None:
            raise ValueError("WeightedEnsemble.fit requires X_val and y_val")

        y_val = np.asarray(y_val)
        n = len(self.models)

        # Collect scores from each model on VAL
        scores_val = np.column_stack([m.predict_proba(X_val) for m in self.models])

        def _neg_fbeta(raw_w):
            # Softmax ensures weights sum to 1 and are positive
            w  = np.exp(raw_w - raw_w.max())
            w /= w.sum()
            composite = scores_val @ w
            # Use best threshold for this weight combination
            lo, hi = np.percentile(composite, [1, 99])
            best = -1.0
            for t in np.linspace(lo, hi, 200):
                yp = (composite > t).astype(int)
                if yp.sum() == 0:
                    continue
                s = fbeta_score(y_val, yp, beta=self.beta, zero_division=0)
                if s > best:
                    best = s
            return -best

        best_result = None
        rng = np.random.default_rng(42)

        for _ in range(self.n_weight_restarts):
            x0     = rng.normal(0, 1, n)
            result = minimize(_neg_fbeta, x0, method="Nelder-Mead",
                              options={"maxiter": 2000, "xatol": 1e-4})
            if best_result is None or result.fun < best_result.fun:
                best_result = result

        raw_w = best_result.x
        w  = np.exp(raw_w - raw_w.max())
        w /= w.sum()
        self.weights_ = w

        logger.info("WeightedEnsemble weights: %s",
                    {m.name: f"{wi:.3f}" for m, wi in zip(self.models, w)})

        # Find threshold on VAL composite score
        composite_val = scores_val @ self.weights_
        self.find_best_threshold(y_val, composite_val, metric="fbeta", beta=self.beta)
        self._fitted = True
        return self

    def predict_proba(self, X) -> np.ndarray:
        scores = np.column_stack([m.predict_proba(X) for m in self.models])
        return scores @ self.weights_

    # fit() doesn't train individual models — just optimizes weights
    # so fit_time is the weight optimization time, tracked by _time_fit


class ANDEnsemble(FraudBaseModel):
    """
    Flag a transaction only when ALL models exceed their individual thresholds.
    Maximises precision at the cost of recall.
    Combined score = geometric mean of all model scores (all on [0,1]).
    """

    def __init__(self, models: list[FraudBaseModel], beta: float = 2.0):
        super().__init__("ANDEnsemble")
        self.models = models
        self.beta   = beta

    def fit(self, X, y, X_val=None, y_val=None):
        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val), self.predict_proba(X_val),
                metric="fbeta", beta=self.beta,
            )
        self._fitted = True
        return self

    def predict_proba(self, X) -> np.ndarray:
        scores = np.column_stack([m.predict_proba(X) for m in self.models])
        # Geometric mean — penalises disagreement between models
        # Valid here because all scores are rank-normalized [0,1]
        log_scores = np.log(np.clip(scores, 1e-9, 1.0))
        return np.exp(log_scores.mean(axis=1))

    def predict(self, X) -> np.ndarray:
        """Flag only when ALL models individually exceed their thresholds."""
        preds = np.column_stack([m.predict(X) for m in self.models])
        return preds.all(axis=1).astype(int)


class ORBoostEnsemble(FraudBaseModel):
    """
    Flag when ANY model is highly confident (score > high_conf_threshold)
    OR when the weighted average exceeds a lower combined threshold.
    Maximises recall for high-value fraud detection.
    """

    def __init__(
        self,
        models: list[FraudBaseModel],
        high_conf_threshold: float = 0.85,
        beta: float = 2.0,
    ):
        super().__init__("ORBoostEnsemble")
        self.models               = models
        self.high_conf_threshold  = high_conf_threshold
        self.beta                 = beta

    def fit(self, X, y, X_val=None, y_val=None):
        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val), self.predict_proba(X_val),
                metric="fbeta", beta=self.beta,
            )
        self._fitted = True
        return self

    def predict_proba(self, X) -> np.ndarray:
        scores = np.column_stack([m.predict_proba(X) for m in self.models])
        avg    = scores.mean(axis=1)
        # Boost composite score when any model is very confident
        max_s  = scores.max(axis=1)
        boost  = np.where(max_s > self.high_conf_threshold, max_s, avg)
        return boost

    def predict(self, X) -> np.ndarray:
        scores = np.column_stack([m.predict_proba(X) for m in self.models])
        # Alert if any model is highly confident OR composite > threshold
        any_high = (scores > self.high_conf_threshold).any(axis=1)
        composite_alert = scores.mean(axis=1) > self.threshold_
        return (any_high | composite_alert).astype(int)


# ---------------------------------------------------------------------------
# Model comparison utility
# ---------------------------------------------------------------------------
def compare_models(
    models: list[FraudBaseModel],
    y_true: np.ndarray,
    X,
    sort_by: str = "f2",
) -> "pd.DataFrame":
    """
    Evaluate all models and return a sorted comparison DataFrame.
    Fixed column names — consistent with evaluate() output keys.
    """
    import pandas as pd

    rows = [m.evaluate(y_true, X) for m in models]
    df   = pd.DataFrame(rows)

    # Formatted display columns
    for col in ["roc_auc", "avg_precision", "f1", "f2", "precision", "recall"]:
        if col in df.columns:
            df[col] = df[col].round(4)

    return df.sort_values(sort_by, ascending=False).reset_index(drop=True)