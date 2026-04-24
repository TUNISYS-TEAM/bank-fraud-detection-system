from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional

import numpy as np

from shared_ml.models.base_model import FraudBaseModel

logger = logging.getLogger(__name__)


class RankNormalizer:
    """
    Mappe les scores d'anomalie bruts sur [0, 1] via la CDF empirique ajustée sur les scores d'entraînement.
    """

    def __init__(self):
        self._sorted_scores: Optional[np.ndarray] = None

    def fit(self, scores: np.ndarray) -> "RankNormalizer":
        scores = np.asarray(scores).reshape(-1)
        if scores.size == 0:
            raise ValueError("RankNormalizer.fit() a reçu un tableau de scores vide.")
        self._sorted_scores = np.sort(scores)
        return self

    def transform(self, scores: np.ndarray) -> np.ndarray:
        if self._sorted_scores is None:
            raise RuntimeError("RankNormalizer.fit() doit être appelé avant transform().")

        scores = np.asarray(scores).reshape(-1)
        ranks = np.searchsorted(self._sorted_scores, scores, side="right")
        return ranks / len(self._sorted_scores)

    def fit_transform(self, scores: np.ndarray) -> np.ndarray:
        return self.fit(scores).transform(scores)


class AnomalyAutoencoder(FraudBaseModel):
    """
    Auto-encodeur entraîné uniquement sur des transactions normales.
    Désormais hébergé dans ml-hybrid-service.
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
        self._keras_config: Optional[str] = None
        self._keras_weights: Optional[list] = None

    def fit(self, X, y, X_val=None, y_val=None):
        from tensorflow import keras
        layers = keras.layers

        y = np.asarray(y)
        X_normal = X[y == 0]
        if len(X_normal) == 0:
            raise ValueError("Autoencoder.fit : aucun échantillon normal (y=0) trouvé.")

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

        recon_train = self._model.predict(X_normal, verbose=0)
        mse_per_feature = np.mean(np.square(X_normal - recon_train), axis=0)

        total_mse = np.sum(mse_per_feature)
        if total_mse > 0:
            self.feature_importances_ = mse_per_feature / total_mse
        else:
            self.feature_importances_ = np.zeros(self.input_dim_)

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

    def get_feature_importance(self, feature_names=None) -> dict:
        if not self._fitted or not hasattr(self, "feature_importances_"):
            return {}

        if feature_names is None:
            feature_names = [f"feature_{i}" for i in range(self.input_dim_)]

        return dict(zip(feature_names, self.feature_importances_.tolist()))

    def _raw_mse(self, X) -> np.ndarray:
        if self._model is None:
            k_config = getattr(self, "_keras_config", None)
            k_weights = getattr(self, "_keras_weights", None)
            if k_config and k_weights:
                logger.info("Rebuilding Keras model from embedded weights...")
                from tensorflow.keras.models import Model
                self._model = Model.from_config(json.loads(k_config))
                self._model.set_weights(k_weights)
            else:
                raise ValueError("AnomalyAutoencoder must be fitted (or self-contained weights missing) before scoring.")

        recon = self._model.predict(X, verbose=0)
        return np.mean(np.square(X - recon), axis=1)

    def predict_proba(self, X) -> np.ndarray:
        return self._normalizer.transform(self._raw_mse(X))

    def to_onnx(self, output_path, feature_names=None):
        raise NotImplementedError("Utilisez l'export Keras SavedModel pour l'Autoencoder.")

    def save(self, path):
        if self._model is None:
            raise ValueError("AnomalyAutoencoder doit être ajusté avant save().")

        import joblib
        import os
        import tempfile
        from datetime import datetime, timezone

        self._keras_config = json.dumps(self._model.get_config())
        self._keras_weights = self._model.get_weights()

        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)

        keras_path = path.parent / f"{path.stem}_keras.keras"
        self._model.save(str(keras_path))

        model_backup = self._model
        self._model = None

        fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
        try:
            os.close(fd)
            joblib.dump(self, tmp, compress=("lz4", 3))
            os.replace(tmp, path)
        finally:
            self._model = model_backup
            if os.path.exists(tmp):
                os.unlink(tmp)

        Path(str(path) + ".meta.json").write_text(
            json.dumps(
                {
                    "name": self.name,
                    "threshold": self.threshold_,
                    "saved_at": datetime.now(timezone.utc).isoformat(),
                    "self_contained": True,
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
    ECOD anomaly model.
    Unsupervised for scoring. Threshold selection must be done outside this class.
    """

    def __init__(self, contamination: float = 0.05, threshold_metric: str = "fbeta", beta: float = 2.0):
        super().__init__("ECOD")
        self.contamination = contamination
        self.threshold_metric = threshold_metric
        self.beta = beta
        self._model = None
        self._normalizer = RankNormalizer()
        self.threshold_raw_: Optional[float] = None

    def fit(self, X, y=None, X_val=None, y_val=None):
        from pyod.models.ecod import ECOD

        self._model = ECOD(contamination=self.contamination)
        self._time_fit(self._model.fit, X)

        raw_train = np.asarray(self._model.decision_scores_).reshape(-1)
        self._normalizer.fit(raw_train)

        self._fitted = True
        return self

    def predict_score(self, X) -> np.ndarray:
        if self._model is None:
            raise RuntimeError("ECODFraudModel must be fitted before predict_score().")
        return np.asarray(self._model.decision_function(X)).reshape(-1)

    def score_to_proba(self, raw_scores) -> np.ndarray:
        return self._normalizer.transform(np.asarray(raw_scores).reshape(-1))

    def predict_score_and_proba(self, X):
        raw = self.predict_score(X)
        proba = self.score_to_proba(raw)
        return raw, proba

    def predict_proba(self, X) -> np.ndarray:
        raw = self.predict_score(X)
        return self.score_to_proba(raw)

    def to_onnx(self, output_path, feature_names=None):
        raise NotImplementedError("L'export ONNX d'ECOD n'est pas supporté.")


class COPODFraudModel(FraudBaseModel):
    """
    COPOD anomaly model.
    Unsupervised for scoring. Threshold selection must be done outside this class.
    """

    def __init__(self, contamination: float = 0.05, threshold_metric: str = "fbeta", beta: float = 2.0):
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

        raw_train = np.asarray(self._model.decision_scores_).reshape(-1)
        self._normalizer.fit(raw_train)

        self._fitted = True
        return self

    def predict_score(self, X) -> np.ndarray:
        if self._model is None:
            raise RuntimeError("COPODFraudModel must be fitted before predict_score().")
        return np.asarray(self._model.decision_function(X)).reshape(-1)

    def score_to_proba(self, raw_scores) -> np.ndarray:
        return self._normalizer.transform(np.asarray(raw_scores).reshape(-1))

    def predict_score_and_proba(self, X):
        raw = self.predict_score(X)
        proba = self.score_to_proba(raw)
        return raw, proba

    def predict_proba(self, X) -> np.ndarray:
        raw = self.predict_score(X)
        return self.score_to_proba(raw)

    def to_onnx(self, output_path, feature_names=None):
        raise NotImplementedError("L'export ONNX de COPOD n'est pas supporté.")