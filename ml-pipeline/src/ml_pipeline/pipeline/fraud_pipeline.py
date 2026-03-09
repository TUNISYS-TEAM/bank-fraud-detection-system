# src/ml_pipeline/pipeline/fraud_pipeline.py
"""
fraud_pipeline.py  —  (production-grade)
============================================

Builds sklearn pipelines combining all feature transformers.
Supports tree‑based, CatBoost, and deep learning models.
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import joblib
import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.pipeline import Pipeline

# Absolute imports from the ml_pipeline package
from ml_pipeline.transformers.behavioral.client_behavior import ClientBehaviorTransformer
from ml_pipeline.transformers.behavioral.temporal_features import TemporalFeatureTransformer
from ml_pipeline.transformers.encoders.categorical_encoder import SafeOneHotEncoder
from ml_pipeline.transformers.encoders.feature_selectors import (
    CatBoostFeatureSelector,
    DLFeatureSelector,
    TreeFeatureSelector,
)
from ml_pipeline.transformers.scalers.monetary_scaler import SafeScaler

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
REQUIRED_INPUT_COLS = [
    "Account_Id", "TX_Time", "TX_Amount",
    "Customer_Postal_Code", "Channel", "category",
]

_BEHAVIORAL_PARAMS = dict(
    smoothing_days=1,
    default_time_gap_days=30,
    strict_as_of=True,
)


# ---------------------------------------------------------------------------
# Small helper transformer — replaces FunctionTransformer for float32 cast
# ---------------------------------------------------------------------------
class ToFloat32Transformer(BaseEstimator, TransformerMixin):
    """Cast DataFrame to float32 numpy array (required for DL / ONNX input)."""

    def __init__(self):
        self._feature_names: list[str] = []

    def fit(self, X, y=None):
        if isinstance(X, pd.DataFrame):
            self._feature_names = list(X.columns)
        return self

    def transform(self, X):
        if isinstance(X, pd.DataFrame):
            return X.values.astype(np.float32)
        return X.astype(np.float32)

    def get_feature_names_out(self, input_features=None) -> list[str]:
        return self._feature_names


# ---------------------------------------------------------------------------
# Pipeline factories
# ---------------------------------------------------------------------------

def create_tree_pipeline() -> Pipeline:
    """LightGBM / XGBoost — no scaling, one-hot encoded categoricals."""
    return Pipeline([
        ("behavior", ClientBehaviorTransformer(**_BEHAVIORAL_PARAMS)),
        ("temporal", TemporalFeatureTransformer()),
        ("onehot",   SafeOneHotEncoder()),
        ("selector", TreeFeatureSelector()),
    ])


def create_catboost_pipeline() -> Pipeline:
    """CatBoost — raw categoricals kept, no one-hot encoding."""
    return Pipeline([
        ("behavior", ClientBehaviorTransformer(**_BEHAVIORAL_PARAMS)),
        ("temporal", TemporalFeatureTransformer()),
        ("selector", CatBoostFeatureSelector()),
    ])


def create_dl_pipeline(scaler_type: str = "robust") -> Pipeline:
    """Autoencoder / dense networks — all-numeric float32 output."""
    return Pipeline([
        ("behavior",  ClientBehaviorTransformer(**_BEHAVIORAL_PARAMS)),
        ("temporal",  TemporalFeatureTransformer()),
        ("onehot",    SafeOneHotEncoder()),
        ("selector",  DLFeatureSelector()),
        ("scaler",    SafeScaler(scaler_type=scaler_type)),
        ("to_float32", ToFloat32Transformer()),
    ])


# ---------------------------------------------------------------------------
# Stateless pipeline builder for ONNX export (without ClientBehaviorTransformer)
# ---------------------------------------------------------------------------
def build_stateless_pipeline(
    model_type: str,
    scaler_type: str = "robust",
    fitted_params: dict | None = None
) -> Pipeline:
    """
    Build pipeline for stateless transformations (temporal → encoding → scaling).
    This is the part that will be exported to ONNX (without ClientBehaviorTransformer).

    Args:
        model_type: 'tree', 'catboost', or 'dl'
        scaler_type: scaler for DL models ('robust', 'standard', 'minmax')
        fitted_params: Optional pre‑fitted parameters from metadata

    Returns:
        Sklearn Pipeline with stateless transformers
    """
    if model_type == "catboost":
        # CatBoost keeps raw categoricals
        pipeline = Pipeline([
            ("temporal", TemporalFeatureTransformer()),
            ("selector", CatBoostFeatureSelector()),
        ])
    elif model_type == "dl":
        # DL models need scaling
        pipeline = Pipeline([
            ("temporal", TemporalFeatureTransformer()),
            ("onehot",   SafeOneHotEncoder()),
            ("selector", DLFeatureSelector()),
            ("scaler",   SafeScaler(scaler_type=scaler_type)),
            ("to_float32", ToFloat32Transformer()),
        ])
    else:
        # Tree models (LightGBM, XGBoost, RF)
        pipeline = Pipeline([
            ("temporal", TemporalFeatureTransformer()),
            ("onehot",   SafeOneHotEncoder()),
            ("selector", TreeFeatureSelector()),
            ("to_float32", ToFloat32Transformer()),
        ])

    # If fitted_params provided, we could set state here
    # This would require transformers to support set_params for fitted state
    return pipeline


def build_full_inference_pipeline(
    model_type: str,
    trained_model,
    scaler_type: str = "robust",
    fitted_params: dict | None = None
) -> Pipeline:
    """
    Build complete inference pipeline: stateless transformations + model.
    This is what gets exported to ONNX.
    """
    stateless = build_stateless_pipeline(model_type, scaler_type, fitted_params)
    return Pipeline(stateless.steps + [("model", trained_model)])


# ---------------------------------------------------------------------------
# FraudFeaturePipeline — wrapper with save/load and metadata sidecar
# ---------------------------------------------------------------------------
class FraudFeaturePipeline:
    """
    Thin wrapper around a sklearn Pipeline that adds:
      - Input validation before fit / transform
      - Atomic save / load (no partial writes)
      - JSON metadata sidecar for Spring Boot consumption
      - Logging

    Parameters
    ----------
    pipeline : sklearn Pipeline
        One of the pipelines created by create_*_pipeline().
    name : str
        Human-readable name used in logs and the metadata sidecar.
    """

    def __init__(self, pipeline: Pipeline, name: str = "unnamed"):
        self.pipeline = pipeline
        self.name = name
        self._lock = threading.RLock()   # re-entrant: fit → transform in same thread OK
        self._fitted = False
        self._fit_ts: Optional[str] = None
        self._feature_count: int = 0
        self._transformer_params: dict = {}  # Store fitted params for ONNX

    # ------------------------------------------------------------------
    # Custom pickling methods to handle threading.Lock
    # ------------------------------------------------------------------
    def __getstate__(self):
        """Remove unpicklable lock before serialization."""
        state = self.__dict__.copy()
        del state['_lock']
        return state

    def __setstate__(self, state):
        """Restore lock after deserialization."""
        self.__dict__.update(state)
        self._lock = threading.RLock()

    # ------------------------------------------------------------------
    def fit(self, X: pd.DataFrame, y=None) -> "FraudFeaturePipeline":
        _validate_input(X, self.name + ".fit")
        logger.info("%s.fit — %d rows", self.name, len(X))

        with self._lock:
            self.pipeline.fit(X[REQUIRED_INPUT_COLS], y)
            self._fitted = True
            self._fit_ts = datetime.now(timezone.utc).isoformat()

            # Extract fitted parameters for ONNX export
            self._extract_fitted_params()

        logger.info("%s.fit — done", self.name)
        return self

    def _extract_fitted_params(self):
        """Extract parameters from fitted transformers for ONNX metadata."""
        params = {}

        # Extract one-hot categories
        if "onehot" in self.pipeline.named_steps:
            ohe = self.pipeline.named_steps["onehot"]
            if hasattr(ohe, "_encoders"):
                params["onehot_categories"] = {
                    col: enc.categories_[0].tolist()
                    for col, enc in ohe._encoders.items()
                }

        # Extract scaler parameters
        if "scaler" in self.pipeline.named_steps:
            scaler = self.pipeline.named_steps["scaler"]
            if hasattr(scaler, "_scaler"):
                s = scaler._scaler
                if hasattr(s, "center_"):
                    params["scaler_mean"] = s.center_.tolist()
                    params["scaler_scale"] = s.scale_.tolist()
                elif hasattr(s, "data_min_"):
                    params["scaler_min"] = s.data_min_.tolist()
                    params["scaler_max"] = s.data_max_.tolist()

        # Extract feature names from last step
        last_step = self.pipeline.steps[-1][1]
        if hasattr(last_step, "get_feature_names_out"):
            params["feature_names"] = list(last_step.get_feature_names_out())
        elif hasattr(last_step, "selected_features_"):
            params["feature_names"] = list(last_step.selected_features_)

        self._transformer_params = params

    # ------------------------------------------------------------------
    def transform(self, X: pd.DataFrame) -> pd.DataFrame | np.ndarray:
        if not self._fitted:
            raise RuntimeError(
                f"{self.name}.transform called before fit(). "
                "Call fit() on TRAIN data first."
            )
        _validate_input(X, self.name + ".transform")
        logger.info("%s.transform — %d rows", self.name, len(X))

        # Thread-safe: sklearn transformers are stateless at transform time
        # (ClientBehaviorTransformer deep-copies state internally)
        result = self.pipeline.transform(X[REQUIRED_INPUT_COLS])

        n_features = result.shape[1] if hasattr(result, "shape") else -1
        logger.info("%s.transform — output shape (%d, %d)", self.name, len(X), n_features)
        self._feature_count = n_features
        return result

    # ------------------------------------------------------------------
    def fit_transform(self, X: pd.DataFrame, y=None) -> pd.DataFrame | np.ndarray:
        return self.fit(X, y).transform(X)

    # ------------------------------------------------------------------
    def save(self, path: str | Path):
        """
        Atomic save: write to a temp file first, then rename.
        Prevents corrupt artifacts if the process is killed mid-write.
        Also writes a JSON metadata sidecar at <path>.meta.json.
        """
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)

        # Atomic write
        fd, tmp_path = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
        try:
            os.close(fd)
            joblib.dump(self, tmp_path, compress=("lz4", 3))
            os.replace(tmp_path, path)   # atomic on POSIX; best-effort on Windows
        except Exception:
            os.unlink(tmp_path)
            raise

        # Write metadata sidecar
        meta = self._build_metadata()
        meta_path = path.with_suffix(".meta.json")
        meta_path.write_text(json.dumps(meta, indent=2))

        logger.info("Pipeline saved → %s  (meta → %s)", path, meta_path)

    # ------------------------------------------------------------------
    @staticmethod
    def load(path: str | Path) -> "FraudFeaturePipeline":
        path = Path(path)
        if not path.exists():
            raise FileNotFoundError(f"Pipeline file not found: {path}")
        obj = joblib.load(path)
        if not isinstance(obj, FraudFeaturePipeline):
            raise TypeError(
                f"Expected FraudFeaturePipeline, got {type(obj).__name__}"
            )
        logger.info("Pipeline loaded ← %s", path)
        return obj

    # ------------------------------------------------------------------
    def _build_metadata(self) -> dict:
        feature_names: list[str] = []
        try:
            last_step = self.pipeline.steps[-1][1]
            if hasattr(last_step, "get_feature_names_out"):
                feature_names = list(last_step.get_feature_names_out())
            elif hasattr(last_step, "selected_features_"):
                feature_names = list(last_step.selected_features_)
        except Exception:
            pass

        # Get pipeline type from steps
        pipeline_type = "tree"
        if "catboost" in str(self.pipeline).lower():
            pipeline_type = "catboost"
        elif "dl" in self.name.lower() or any(s[0] == "scaler" for s in self.pipeline.steps):
            pipeline_type = "dl"

        return {
            "name": self.name,
            "pipeline_type": pipeline_type,
            "fitted_at": self._fit_ts,
            "feature_count": self._feature_count or len(feature_names),
            "feature_names": feature_names,
            "pipeline_steps": [name for name, _ in self.pipeline.steps],
            "required_input": REQUIRED_INPUT_COLS,
            "transformer_params": self._transformer_params,  # For ONNX export
        }

    def __repr__(self) -> str:
        status = "fitted" if self._fitted else "not fitted"
        return f"FraudFeaturePipeline(name='{self.name}', {status})"


# ---------------------------------------------------------------------------
# Convenience: build all four pipelines at once
# ---------------------------------------------------------------------------
def build_all_pipelines(
    df_train: pd.DataFrame,
    df_val: pd.DataFrame,
    df_test: pd.DataFrame,
    output_dir: str | Path | None = None,
) -> dict[str, dict]:
    """
    Fit all four pipeline variants on TRAIN and transform all splits.

    Returns
    -------
    dict  keyed by pipeline name, each value:
        {'pipeline': FraudFeaturePipeline,
         'X_train': ..., 'X_val': ..., 'X_test': ...}
    """
    configs = [
        ("tree",        create_tree_pipeline()),
        ("catboost",    create_catboost_pipeline()),
        ("dl_robust",   create_dl_pipeline("robust")),
        ("dl_standard", create_dl_pipeline("standard")),
    ]

    results = {}
    for name, pipe in configs:
        logger.info("Building pipeline: %s", name.upper())
        print(f"\n{'─'*60}\n  Pipeline: {name.upper()}\n{'─'*60}")

        fp = FraudFeaturePipeline(pipe, name=name)
        fp.fit(df_train)

        X_tr = fp.transform(df_train)
        X_val = fp.transform(df_val)
        X_te = fp.transform(df_test)

        _shape = lambda x: x.shape if hasattr(x, "shape") else (len(x), "?")
        print(f"  TRAIN : {_shape(X_tr)}")
        print(f"  VAL   : {_shape(X_val)}")
        print(f"  TEST  : {_shape(X_te)}")

        if output_dir:
            fp.save(Path(output_dir) / f"pipeline_{name}.joblib")

        results[name] = {
            "pipeline": fp,
            "X_train": X_tr,
            "X_val": X_val,
            "X_test": X_te,
        }

    return results


# ---------------------------------------------------------------------------
# Private helpers
# ---------------------------------------------------------------------------
def _validate_input(X: pd.DataFrame, context: str):
    if not isinstance(X, pd.DataFrame):
        raise TypeError(f"{context}: expected pd.DataFrame, got {type(X).__name__}")
    missing = set(REQUIRED_INPUT_COLS) - set(X.columns)
    if missing:
        raise ValueError(
            f"{context}: missing required columns: {sorted(missing)}"
        )
    if len(X) == 0:
        raise ValueError(f"{context}: DataFrame is empty")