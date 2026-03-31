from __future__ import annotations

import json
import logging
import os
import tempfile
import time
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from pathlib import Path

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
    """
    Classe de base abstraite partagée par tous les modèles de détection de fraude.
    """

    def __init__(self, name: str):
        self.name = name
        self.threshold_: float = 0.5
        self.fit_time_: float = 0.0
        self._fitted: bool = False

    @abstractmethod
    def fit(self, X, y, X_val=None, y_val=None) -> "FraudBaseModel":
        """Entraîne le modèle."""
        raise NotImplementedError

    @abstractmethod
    def predict_proba(self, X) -> np.ndarray:
        """Retourne les scores de fraude ou les probabilités dans l'intervalle [0, 1]."""
        raise NotImplementedError

    def predict(self, X) -> np.ndarray:
        """Retourne des prédictions binaires en utilisant le seuil de décision enregistré."""
        return (self.predict_proba(X) > self.threshold_).astype(int)

    def _time_fit(self, fn, *args, **kwargs):
        t0 = time.time()
        result = fn(*args, **kwargs)
        self.fit_time_ = time.time() - t0
        self._fitted = True
        logger.info("%s trained in %.1fs", self.name, self.fit_time_)
        return result

    def find_best_threshold(
        self,
        y_val: np.ndarray,
        scores_val: np.ndarray,
        metric: str = "fbeta",
        beta: float = 2.0,
        n_steps: int = 300,
    ) -> float:
        """
        Recherche le meilleur seuil sur les scores de validation.

        La métrique par défaut est F-beta avec beta=2.0, ce qui donne la priorité au rappel (recall).
        """
        y_val = np.asarray(y_val).reshape(-1)
        scores_val = np.asarray(scores_val).reshape(-1)

        if y_val.size == 0 or scores_val.size == 0:
            raise ValueError("y_val and scores_val must not be empty.")

        if y_val.shape[0] != scores_val.shape[0]:
            raise ValueError("y_val and scores_val must have the same length.")

        if metric not in {"f1", "fbeta"}:
            raise ValueError("metric must be either 'f1' or 'fbeta'.")

        lo = float(np.percentile(scores_val, 1))
        hi = float(np.percentile(scores_val, 99))
        best_score = -1.0
        best_threshold = float(np.median(scores_val))

        for t in np.linspace(lo, hi, n_steps):
            y_pred = (scores_val > t).astype(int)
            if y_pred.sum() == 0:
                continue

            score = (
                f1_score(y_val, y_pred, zero_division=0)
                if metric == "f1"
                else fbeta_score(y_val, y_pred, beta=beta, zero_division=0)
            )

            if score > best_score:
                best_score = float(score)
                best_threshold = float(t)

        self.threshold_ = best_threshold
        logger.info(
            "%s threshold=%.4f %s@β%.1f=%.4f [VAL]",
            self.name,
            best_threshold,
            metric,
            beta,
            best_score,
        )
        return best_threshold

    def evaluate(self, y_true, X, threshold=None, split_name="TEST") -> dict:
        """
        Évalue le modèle sur un ensemble de données spécifique.
        """
        if not self._fitted:
            raise ValueError(f"{self.name} must be fitted before evaluate().")

        y_true = np.asarray(y_true).reshape(-1)
        threshold_to_use = threshold if threshold is not None else self.threshold_

        scores = self.predict_proba(X)
        y_pred = (scores > threshold_to_use).astype(int)

        metrics = {
            "model": self.name,
            "split": split_name,
            "threshold": float(threshold_to_use),
            "roc_auc": _safe(roc_auc_score, y_true, scores),
            "avg_precision": _safe(average_precision_score, y_true, scores),
            "f1": float(f1_score(y_true, y_pred, zero_division=0)),
            "f2": float(fbeta_score(y_true, y_pred, beta=2, zero_division=0)),
            "precision": float(precision_score(y_true, y_pred, zero_division=0)),
            "recall": float(recall_score(y_true, y_pred, zero_division=0)),
            "avg_fraud_score": float(scores.mean()),
            "n_flagged": int(y_pred.sum()),
            "fraud_rate": float(y_true.mean()),
            "fit_time_sec": self.fit_time_,
        }

        logger.info(
            "%s [%s] AUC=%.4f F1=%.4f F2=%.4f P=%.4f R=%.4f flagged=%d",
            self.name,
            split_name,
            metrics["roc_auc"],
            metrics["f1"],
            metrics["f2"],
            metrics["precision"],
            metrics["recall"],
            metrics["n_flagged"],
        )
        return metrics

    def save(self, path: str | Path):
        """
        Sauvegarde le modèle avec une écriture atomique joblib et des métadonnées JSON.
        Fallback automatique vers gzip si lz4 n'est pas installé.
        """
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)

        fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
        try:
            os.close(fd)
            try:
                joblib.dump(self, tmp, compress=("lz4", 3))
            except ValueError:
                joblib.dump(self, tmp, compress=("gzip", 3))
            os.replace(tmp, path)
        except Exception:
            if os.path.exists(tmp):
                os.unlink(tmp)
            raise

        Path(str(path) + ".meta.json").write_text(
            json.dumps(
                {
                    "name": self.name,
                    "class": type(self).__name__,
                    "threshold": self.threshold_,
                    "fit_time_sec": self.fit_time_,
                    "saved_at": datetime.now(timezone.utc).isoformat(),
                },
                indent=2,
            ),
            encoding="utf-8",
        )

        logger.info("%s saved → %s", self.name, path)

    @classmethod
    def load(cls, path) -> "FraudBaseModel":
        obj = joblib.load(path)
        logger.info("Model loaded ← %s", path)
        return obj

    @abstractmethod
    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """
        Exporte le modèle au format ONNX.
        """
        raise NotImplementedError

    def get_feature_importance(self) -> dict:
        """Retourne l'importance des caractéristiques si supporté, sinon un dictionnaire vide."""
        return {}


def _safe(fn, y, s) -> float:
    try:
        return float(fn(y, s))
    except Exception:
        return 0.0