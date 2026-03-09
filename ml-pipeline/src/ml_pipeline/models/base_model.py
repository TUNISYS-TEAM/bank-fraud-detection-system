"""
Shared base class for all fraud detection models.
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
import time
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import joblib
import numpy as np
from sklearn.metrics import (
    average_precision_score,
    f1_score,
    fbeta_score,
    precision_score,
    recall_score,
    roc_auc_score,
)

logger = logging.getLogger(__name__)


class FraudBaseModel(ABC):
    """Abstract base class for fraud detection models."""

    def __init__(self, name: str):
        self.name = name
        self.threshold_: float = 0.5
        self.fit_time_: float = 0.0
        self._fitted: bool = False

    @abstractmethod
    def fit(self, X, y, X_val=None, y_val=None) -> "FraudBaseModel":
        """Train the model. If X_val/y_val provided, use for early stopping/threshold tuning."""
        pass

    @abstractmethod
    def predict_proba(self, X) -> np.ndarray:
        """Return probability of fraud (positive class) in [0,1]."""
        pass

    def predict(self, X) -> np.ndarray:
        """Return binary predictions using the stored threshold."""
        return (self.predict_proba(X) > self.threshold_).astype(int)

    def _time_fit(self, fn, *a, **kw):
        t0 = time.time()
        r = fn(*a, **kw)
        self.fit_time_ = time.time() - t0
        self._fitted = True
        logger.info("%s trained in %.1fs", self.name, self.fit_time_)
        return r

    def find_best_threshold(
        self,
        y_val: np.ndarray,
        scores_val: np.ndarray,
        metric: str = "fbeta",
        beta: float = 2.0,
        n_steps: int = 300,
    ) -> float:
        """
        Find optimal threshold on validation scores.
        Uses fbeta (beta=2) by default: recall weighted 2× vs precision.
        """
        lo = float(np.percentile(scores_val, 1))
        hi = float(np.percentile(scores_val, 99))
        best_s, best_t = -1.0, float(np.median(scores_val))

        for t in np.linspace(lo, hi, n_steps):
            y_pred = (scores_val > t).astype(int)
            if y_pred.sum() == 0:
                continue
            if metric == "f1":
                s = f1_score(y_val, y_pred, zero_division=0)
            else:
                s = fbeta_score(y_val, y_pred, beta=beta, zero_division=0)
            if s > best_s:
                best_s, best_t = s, float(t)

        self.threshold_ = best_t
        logger.info("%s threshold=%.4f  %s@β%.1f=%.4f [VAL]",
                    self.name, best_t, metric, beta, best_s)
        return best_t

    def evaluate(self, y_true, X, threshold=None, split_name="TEST") -> dict:
        """Compute evaluation metrics on given data."""
        t = threshold if threshold is not None else self.threshold_
        scores = self.predict_proba(X)
        y_pred = (scores > t).astype(int)
        m = {
            "model": self.name,
            "split": split_name,
            "threshold": float(t),
            "roc_auc": _safe(roc_auc_score, y_true, scores),
            "avg_precision": _safe(average_precision_score, y_true, scores),
            "f1": float(f1_score(y_true, y_pred, zero_division=0)),
            "f2": float(fbeta_score(y_true, y_pred, beta=2, zero_division=0)),
            "precision": float(precision_score(y_true, y_pred, zero_division=0)),
            "recall": float(recall_score(y_true, y_pred, zero_division=0)),
            "n_flagged": int(y_pred.sum()),
            "fraud_rate": float(y_true.mean()),
            "fit_time_sec": self.fit_time_,
        }
        logger.info("%s [%s] AUC=%.4f F1=%.4f F2=%.4f P=%.4f R=%.4f flagged=%d",
                    self.name, split_name, m["roc_auc"], m["f1"], m["f2"],
                    m["precision"], m["recall"], m["n_flagged"])
        return m

    def save(self, path: str | Path):
        """Save model using joblib with atomic write and JSON metadata."""
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
        try:
            os.close(fd)
            joblib.dump(self, tmp, compress=("lz4", 3))
            os.replace(tmp, path)
        except Exception:
            os.unlink(tmp)
            raise
        # Write metadata
        (path.with_suffix(".meta.json")).write_text(
            json.dumps(
                {
                    "name": self.name,
                    "class": type(self).__name__,
                    "threshold": self.threshold_,
                    "fit_time_sec": self.fit_time_,
                    "saved_at": datetime.now(timezone.utc).isoformat(),
                },
                indent=2,
            )
        )
        logger.info("%s saved → %s", self.name, path)

    @classmethod
    def load(cls, path) -> "FraudBaseModel":
        """Load model from joblib file."""
        obj = joblib.load(path)
        logger.info("Model loaded ← %s", path)
        return obj

    @abstractmethod
    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """
        Export model to ONNX format.

        Args:
            output_path: Where to save the ONNX file
            feature_names: Names of input features (for metadata)
        """
        pass

    def get_feature_importance(self) -> dict:
        """Return feature importance if available, else empty dict."""
        return {}


def _safe(fn, y, s) -> float:
    try:
        return float(fn(y, s))
    except Exception:
        return 0.0