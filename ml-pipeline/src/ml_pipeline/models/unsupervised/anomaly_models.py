from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional

import numpy as np

from ml_pipeline.models.base_model import FraudBaseModel

logger = logging.getLogger(__name__)


class RankNormalizer:
    """
    Maps raw anomaly scores to [0, 1] via the empirical CDF fitted on training scores.

    This makes anomaly scores stable across batches and comparable with other
    model outputs inside hybrid or ensemble strategies.
    """

    def __init__(self):
        self._sorted_scores: Optional[np.ndarray] = None

    def fit(self, scores: np.ndarray) -> "RankNormalizer":
        scores = np.asarray(scores).reshape(-1)
        if scores.size == 0:
            raise ValueError("RankNormalizer.fit() received an empty score array.")
        self._sorted_scores = np.sort(scores)
        return self

    def transform(self, scores: np.ndarray) -> np.ndarray:
        if self._sorted_scores is None:
            raise RuntimeError("RankNormalizer.fit() must be called before transform().")

        scores = np.asarray(scores).reshape(-1)
        ranks = np.searchsorted(self._sorted_scores, scores, side="right")
        return ranks / len(self._sorted_scores)

    def fit_transform(self, scores: np.ndarray) -> np.ndarray:
        return self.fit(scores).transform(scores)


class AnomalyAutoencoder(FraudBaseModel):
    """
    Autoencoder trained on normal transactions only.
    High reconstruction error indicates anomalous behavior.
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
        self.encoding_dim = encoding_dim
        self.epochs = epochs
        self.batch_size = batch_size
        self.threshold_metric = threshold_metric
        self.beta = beta

        self._model = None
        self._normalizer = RankNormalizer()
        self.input_dim_: int = 0

    def fit(self, X, y, X_val=None, y_val=None):
        from tensorflow import keras

        layers = keras.layers

        y = np.asarray(y)
        X_normal = X[y == 0]
        if len(X_normal) == 0:
            raise ValueError("Autoencoder.fit: no normal (y=0) samples found in training data.")

        self.input_dim_ = X.shape[1]

        inp = keras.Input(shape=(self.input_dim_,))
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

        callbacks = [
            keras.callbacks.EarlyStopping(
                monitor="val_loss" if val_data else "loss",
                patience=5,
                restore_best_weights=True,
            )
        ]

        def _fit():
            self._model.fit(
                X_normal,
                X_normal,
                validation_data=val_data,
                epochs=self.epochs,
                batch_size=self.batch_size,
                shuffle=True,
                callbacks=callbacks,
                verbose=0,
            )

        self._time_fit(_fit)

        train_mse = self._raw_mse(X)
        self._normalizer.fit(train_mse)
        logger.info("Autoencoder: rank normalizer fitted on %d training scores", len(train_mse))

        if X_val is not None and y_val is not None:
            scores_val = self._normalizer.transform(self._raw_mse(X_val))
            self.find_best_threshold(
                np.asarray(y_val),
                scores_val,
                metric=self.threshold_metric,
                beta=self.beta,
            )

        self._fitted = True
        return self

    def _raw_mse(self, X) -> np.ndarray:
        if self._model is None:
            raise ValueError("AnomalyAutoencoder must be fitted before scoring.")
        recon = self._model.predict(X, verbose=0)
        return np.mean(np.square(X - recon), axis=1)

    def predict_proba(self, X) -> np.ndarray:
        """Return rank-normalized anomaly scores in [0, 1]."""
        return self._normalizer.transform(self._raw_mse(X))

    def to_onnx(self, output_path, feature_names=None):
        raise NotImplementedError(
            "Autoencoder ONNX export is not supported in this project. "
            "Use Keras SavedModel export instead."
        )

    def save(self, path):
        """Save joblib state + Keras model file + metadata JSON."""
        if self._model is None:
            raise ValueError("AnomalyAutoencoder must be fitted before save().")

        import joblib
        import os
        import tempfile
        from datetime import datetime, timezone

        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)

        keras_path = path.parent / f"{path.stem}_keras.keras"
        self._model.save(str(keras_path))

        model_backup = self._model
        self._model = None

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
        finally:
            self._model = model_backup

        Path(str(path) + ".meta.json").write_text(
            json.dumps(
                {
                    "name": self.name,
                    "keras_path": str(keras_path),
                    "threshold": self.threshold_,
                    "saved_at": datetime.now(timezone.utc).isoformat(),
                },
                indent=2,
            ),
        encoding="utf-8",
    )

    @classmethod
    def load(cls, path):
        import joblib
        from tensorflow import keras

        path = Path(path)
        obj = joblib.load(path)
        meta = json.loads(Path(str(path) + ".meta.json").read_text(encoding="utf-8"))
        obj._model = keras.models.load_model(meta["keras_path"])
        return obj


class ECODFraudModel(FraudBaseModel):
    """
    ECOD unsupervised anomaly detector with rank-normalized output scores.
    """

    def __init__(
        self,
        contamination: float = 0.05,
        threshold_metric: str = "fbeta",
        beta: float = 2.0,
    ):
        super().__init__("ECOD")
        self.contamination = contamination
        self.threshold_metric = threshold_metric
        self.beta = beta

        self._model = None
        self._normalizer = RankNormalizer()

    def fit(self, X, y=None, X_val=None, y_val=None):
        from pyod.models.ecod import ECOD

        self._model = ECOD(contamination=self.contamination)
        self._time_fit(self._model.fit, X)

        raw_train = self._model.decision_function(X)
        self._normalizer.fit(raw_train)
        logger.info("ECOD: rank normalizer fitted on %d training scores", len(raw_train))

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
            raise ValueError("ECODFraudModel must be fitted before predict_proba().")
        raw = self._model.decision_function(X)
        return self._normalizer.transform(raw)

    def to_onnx(self, output_path, feature_names=None):
        raise NotImplementedError("ECOD ONNX export not supported.")


class COPODFraudModel(FraudBaseModel):
    """
    COPOD unsupervised anomaly detector with rank-normalized output scores.
    """

    def __init__(
        self,
        contamination: float = 0.05,
        threshold_metric: str = "fbeta",
        beta: float = 2.0,
    ):
        super().__init__("COPOD")
        self.contamination = contamination
        self.threshold_metric = threshold_metric
        self.beta = beta

        self._model = None
        self._normalizer = RankNormalizer()

    def fit(self, X, y=None, X_val=None, y_val=None):
        from pyod.models.copod import COPOD

        self._model = COPOD(contamination=self.contamination)
        self._time_fit(self._model.fit, X)

        raw_train = self._model.decision_function(X)
        self._normalizer.fit(raw_train)
        logger.info("COPOD: rank normalizer fitted on %d training scores", len(raw_train))

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
            raise ValueError("COPODFraudModel must be fitted before predict_proba().")
        raw = self._model.decision_function(X)
        return self._normalizer.transform(raw)

    def to_onnx(self, output_path, feature_names=None):
        raise NotImplementedError("COPOD ONNX export not supported.")