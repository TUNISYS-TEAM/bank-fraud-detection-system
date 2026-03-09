# src/ml_pipeline/models/unsupervised/anomaly_models.py
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
    Maps raw anomaly scores to [0,1] via the empirical CDF fitted on TRAIN.
    """

    def __init__(self):
        self._sorted_scores: Optional[np.ndarray] = None

    def fit(self, scores: np.ndarray) -> "RankNormalizer":
        self._sorted_scores = np.sort(scores)
        return self

    def transform(self, scores: np.ndarray) -> np.ndarray:
        if self._sorted_scores is None:
            raise RuntimeError("RankNormalizer.fit() must be called first")
        ranks = np.searchsorted(self._sorted_scores, scores, side="right")
        return ranks / len(self._sorted_scores)

    def fit_transform(self, scores: np.ndarray) -> np.ndarray:
        return self.fit(scores).transform(scores)


class AnomalyAutoencoder(FraudBaseModel):
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
        import tensorflow as tf
        from tensorflow import keras
        layers = keras.layers

        y = np.asarray(y)
        X_normal = X[y == 0]
        if len(X_normal) == 0:
            raise ValueError("Autoencoder.fit: no normal (y=0) samples in TRAIN")

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

        # Fit rank normalizer on TRAIN MSE scores
        train_mse = self._raw_mse(X)
        self._normalizer.fit(train_mse)
        logger.info("Autoencoder: rank normalizer fitted on %d TRAIN scores", len(train_mse))

        if X_val is not None and y_val is not None:
            scores_val = self._normalizer.transform(self._raw_mse(X_val))
            self.find_best_threshold(
                np.asarray(y_val),
                scores_val,
                metric=self.threshold_metric,
                beta=self.beta,
            )
        return self

    def _raw_mse(self, X) -> np.ndarray:
        recon = self._model.predict(X, verbose=0)
        return np.mean(np.square(X - recon), axis=1)

    def predict_proba(self, X) -> np.ndarray:
        return self._normalizer.transform(self._raw_mse(X))

    def save(self, path):
        import joblib
        import os
        import tempfile
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
            os.unlink(tmp)
            raise
        finally:
            self._model = model_backup
        (path.with_suffix(".meta.json")).write_text(
            json.dumps(
                {
                    "name": self.name,
                    "keras_path": str(keras_path),
                    "threshold": self.threshold_,
                    "saved_at": datetime.now(timezone.utc).isoformat(),
                },
                indent=2,
            )
        )

    @classmethod
    def load(cls, path):
        import joblib
        import json
        from tensorflow import keras

        path = Path(path)
        obj = joblib.load(path)
        meta = json.loads((path.with_suffix(".meta.json")).read_text())
        obj._model = keras.models.load_model(meta["keras_path"])
        return obj

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """Export Autoencoder to ONNX via tf2onnx."""
        try:
            import tf2onnx
            import tensorflow as tf
        except ImportError:
            raise ImportError("Please install tf2onnx and tensorflow")

        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        spec = (tf.TensorSpec((None, self.input_dim_), tf.float32, name="input"),)
        _, _ = tf2onnx.convert.from_keras(
            self._model,
            input_signature=spec,
            opset=13,
            output_path=str(output_path),
        )

        meta = {
            "model_type": "autoencoder",
            "input_dim": self.input_dim_,
            "encoding_dim": self.encoding_dim,
            "feature_names": feature_names or [],
            "threshold": self.threshold_,
        }
        meta_path = output_path.with_suffix(".meta.json")
        meta_path.write_text(json.dumps(meta, indent=2))

        logger.info(f"Autoencoder exported to ONNX: {output_path}")
        return output_path


class ECODFraudModel(FraudBaseModel):
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
        logger.info("ECOD: rank normalizer fitted on %d TRAIN scores", len(raw_train))

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val),
                self.predict_proba(X_val),
                metric=self.threshold_metric,
                beta=self.beta,
            )
        return self

    def predict_proba(self, X) -> np.ndarray:
        raw = self._model.decision_function(X)
        return self._normalizer.transform(raw)

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """ECOD model cannot be exported to ONNX easily."""
        raise NotImplementedError(
            "ECOD (pyod) does not have built-in ONNX export. "
            "Consider using a different model for ONNX deployment."
        )


class COPODFraudModel(FraudBaseModel):
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
        logger.info("COPOD: rank normalizer fitted on %d TRAIN scores", len(raw_train))

        if X_val is not None and y_val is not None:
            self.find_best_threshold(
                np.asarray(y_val),
                self.predict_proba(X_val),
                metric=self.threshold_metric,
                beta=self.beta,
            )
        return self

    def predict_proba(self, X) -> np.ndarray:
        raw = self._model.decision_function(X)
        return self._normalizer.transform(raw)

    def to_onnx(self, output_path: str | Path, feature_names: list[str] | None = None):
        """COPOD model cannot be exported to ONNX easily."""
        raise NotImplementedError(
            "COPOD (pyod) does not have built-in ONNX export. "
            "Consider using a different model for ONNX deployment."
        )