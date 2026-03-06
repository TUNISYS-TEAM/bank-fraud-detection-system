"""
anomaly_models.py — Autoencoder, ECOD, COPOD unsupervised detectors.

Fixes vs notebook
-----------------
1. Score normalization uses rank-based (percentile) normalization instead of
   batch min-max. Batch min-max is dataset-dependent — the same transaction
   gets a different score depending on what else is in the batch, which breaks
   at serving time. Rank normalization maps scores to [0,1] via the empirical
   CDF fitted on TRAIN, making scores stable and comparable across batches.

2. Autoencoder threshold search uses the fine-grained fbeta grid from base
   class instead of only checking 5 percentile points.

3. All models expose a stable predict_proba(X) → [0,1] via the fitted CDF.
"""

from __future__ import annotations
import logging
from typing import Optional
import numpy as np
from models.base_model import FraudBaseModel

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Rank-based score normalizer — the core fix for hybrid scoring
# ---------------------------------------------------------------------------
class RankNormalizer:
    """
    Maps raw anomaly scores to [0,1] via the empirical CDF fitted on TRAIN.

    Why this matters
    ----------------
    Batch min-max: score_norm = (s - batch_min) / (batch_max - batch_min)
    → The same transaction scores differently depending on what else is in the
      batch. At Spring Boot inference time (single transaction) this collapses
      to 0 or 1 trivially. It also makes the LightGBM+ECOD geometric mean
      mathematically meaningless.

    Rank normalization: score_norm = ECDF(s) fitted on TRAIN
    → Stable, batch-independent, interpretable as "percentile of anomaly".
    """

    def __init__(self):
        self._sorted_scores: Optional[np.ndarray] = None

    def fit(self, scores: np.ndarray) -> "RankNormalizer":
        self._sorted_scores = np.sort(scores)
        return self

    def transform(self, scores: np.ndarray) -> np.ndarray:
        if self._sorted_scores is None:
            raise RuntimeError("RankNormalizer.fit() must be called first")
        # searchsorted gives rank → divide by n for CDF value
        ranks = np.searchsorted(self._sorted_scores, scores, side="right")
        return ranks / len(self._sorted_scores)

    def fit_transform(self, scores: np.ndarray) -> np.ndarray:
        return self.fit(scores).transform(scores)


# ---------------------------------------------------------------------------
class AnomalyAutoencoder(FraudBaseModel):
    """
    Autoencoder trained on NORMAL transactions only.
    High reconstruction error = anomalous = likely fraud.

    Architecture: input → 64 → encoding_dim → 64 → input
    """

    def __init__(
        self,
        encoding_dim: int = 32,
        epochs: int = 20,
        batch_size: int = 1024,
        threshold_metric: str = "fbeta",
        beta: float = 2.0,
    ):
        super().__init__("Autoencoder")
        self.encoding_dim     = encoding_dim
        self.epochs           = epochs
        self.batch_size       = batch_size
        self.threshold_metric = threshold_metric
        self.beta             = beta
        self._model           = None
        self._normalizer      = RankNormalizer()
        self.input_dim_: int  = 0

    # ------------------------------------------------------------------
    def fit(self, X, y, X_val=None, y_val=None):
        import tensorflow as tf
        from tensorflow import keras
        layers = keras.layers

        y = np.asarray(y)
        X_normal = X[y == 0]
        if len(X_normal) == 0:
            raise ValueError("Autoencoder.fit: no normal (y=0) samples in TRAIN")

        self.input_dim_ = X.shape[1]
        inp     = keras.Input(shape=(self.input_dim_,))
        encoded = layers.Dense(64, activation="relu")(inp)
        encoded = layers.Dropout(0.2)(encoded)
        encoded = layers.Dense(self.encoding_dim, activation="relu")(encoded)
        decoded = layers.Dense(64, activation="relu")(encoded)
        decoded = layers.Dropout(0.2)(decoded)
        decoded = layers.Dense(self.input_dim_, activation="linear")(decoded)

        self._model = keras.Model(inp, decoded)
        self._model.compile(optimizer="adam", loss="mse")

        val_data = None
        if X_val is not None:
            X_val_normal = X_val[np.asarray(y_val) == 0] if y_val is not None else X_val
            if len(X_val_normal) > 0:
                val_data = (X_val_normal, X_val_normal)

        callbacks = [keras.callbacks.EarlyStopping(
            monitor="val_loss" if val_data else "loss",
            patience=5, restore_best_weights=True,
        )]

        def _fit():
            self._model.fit(
                X_normal, X_normal,
                validation_data=val_data,
                epochs=self.epochs,
                batch_size=self.batch_size,
                shuffle=True,
                callbacks=callbacks,
                verbose=0,
            )

        self._time_fit(_fit)

        # Fit rank normalizer on TRAIN MSE scores
        train_mse = self._raw_mse(X)
        self._normalizer.fit(train_mse)
        logger.info("Autoencoder: rank normalizer fitted on %d TRAIN scores", len(train_mse))

        # Find threshold on VAL using normalized scores
        if X_val is not None and y_val is not None:
            scores_val = self._normalizer.transform(self._raw_mse(X_val))
            self.find_best_threshold(
                np.asarray(y_val), scores_val,
                metric=self.threshold_metric, beta=self.beta,
            )
        return self

    def _raw_mse(self, X) -> np.ndarray:
        recon = self._model.predict(X, verbose=0)
        return np.mean(np.square(X - recon), axis=1)

    def predict_proba(self, X) -> np.ndarray:
        """Returns rank-normalized anomaly score in [0,1]."""
        return self._normalizer.transform(self._raw_mse(X))

    def save(self, path):
        """Override to save Keras model separately."""
        import joblib, json, os, tempfile
        from pathlib import Path
        from datetime import datetime, timezone

        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        keras_path = path.parent / (path.stem + "_keras")
        self._model.save(str(keras_path))
        model_backup = self._model
        self._model = None
        fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
        try:
            os.close(fd)
            joblib.dump(self, tmp, compress=("lz4", 3))
            os.replace(tmp, path)
        except Exception:
            os.unlink(tmp); raise
        finally:
            self._model = model_backup
        Path(str(path) + ".meta.json").write_text(json.dumps({
            "name": self.name, "keras_path": str(keras_path),
            "threshold": self.threshold_, "saved_at": datetime.now(timezone.utc).isoformat(),
        }, indent=2))

    @classmethod
    def load(cls, path):
        import joblib, json
        from pathlib import Path
        from tensorflow import keras
        path = Path(path)
        obj  = joblib.load(path)
        meta = json.loads(Path(str(path) + ".meta.json").read_text())
        obj._model = keras.models.load_model(meta["keras_path"])
        return obj


# ---------------------------------------------------------------------------
class ECODFraudModel(FraudBaseModel):
    """
    ECOD (Empirical Cumulative distribution-based Outlier Detection).
    Unsupervised — fits on TRAIN without labels.
    """

    def __init__(
        self,
        contamination: float = 0.05,
        threshold_metric: str = "fbeta",
        beta: float = 2.0,
    ):
        super().__init__("ECOD")
        self.contamination    = contamination
        self.threshold_metric = threshold_metric
        self.beta             = beta
        self._model           = None
        self._normalizer      = RankNormalizer()

    def fit(self, X, y=None, X_val=None, y_val=None):
        from pyod.models.ecod import ECOD
        self._model = ECOD(contamination=self.contamination)
        self._time_fit(self._model.fit, X)

        raw_train = self._model.decision_function(X)
        self._normalizer.fit(raw_train)
        logger.info("ECOD: rank normalizer fitted on %d TRAIN scores", len(raw_train))

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val), self.predict_proba(X_val),
                metric=self.threshold_metric, beta=self.beta,
            )
        return self

    def predict_proba(self, X) -> np.ndarray:
        raw = self._model.decision_function(X)
        return self._normalizer.transform(raw)


# ---------------------------------------------------------------------------
class COPODFraudModel(FraudBaseModel):
    """
    COPOD (Copula-based Outlier Detection).
    Unsupervised — fits on TRAIN without labels.
    """

    def __init__(
        self,
        contamination: float = 0.05,
        threshold_metric: str = "fbeta",
        beta: float = 2.0,
    ):
        super().__init__("COPOD")
        self.contamination    = contamination
        self.threshold_metric = threshold_metric
        self.beta             = beta
        self._model           = None
        self._normalizer      = RankNormalizer()

    def fit(self, X, y=None, X_val=None, y_val=None):
        from pyod.models.copod import COPOD
        self._model = COPOD(contamination=self.contamination)
        self._time_fit(self._model.fit, X)

        raw_train = self._model.decision_function(X)
        self._normalizer.fit(raw_train)
        logger.info("COPOD: rank normalizer fitted on %d TRAIN scores", len(raw_train))

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val), self.predict_proba(X_val),
                metric=self.threshold_metric, beta=self.beta,
            )
        return self

    def predict_proba(self, X) -> np.ndarray:
        raw = self._model.decision_function(X)
        return self._normalizer.transform(raw)