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
from sklearn.base import BaseEstimator, TransformerMixin, clone
from sklearn.pipeline import Pipeline

from shared_ml.preprocessing.behavioral.client_behavior import ClientBehaviorTransformer
from shared_ml.preprocessing.behavioral.temporal_features import TemporalFeatureTransformer
from shared_ml.preprocessing.encoders.categorical_encoder import SafeOneHotEncoder
from shared_ml.preprocessing.encoders.feature_selectors import (
    CatBoostFeatureSelector,
    DLFeatureSelector,
    TreeFeatureSelector,
)
from shared_ml.preprocessing.scalers.monetary_scaler import SafeScaler

logger = logging.getLogger(__name__)

REQUIRED_INPUT_COLS = [
    "Account_Id",
    "TX_Time",
    "TX_Amount",
    "Customer_Postal_Code",
    "Channel",
    "category",
]

_BEHAVIORAL_PARAMS = dict(
    smoothing_days=1,
    default_time_gap_days=30,
    strict_as_of=True,
)


class ToFloat32Transformer(BaseEstimator, TransformerMixin):
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


def create_tree_pipeline() -> Pipeline:
    return Pipeline([
        ("behavior", ClientBehaviorTransformer(**_BEHAVIORAL_PARAMS)),
        ("temporal", TemporalFeatureTransformer()),
        ("onehot", SafeOneHotEncoder()),
        ("selector", TreeFeatureSelector()),
    ])


def create_catboost_pipeline() -> Pipeline:
    return Pipeline([
        ("behavior", ClientBehaviorTransformer(**_BEHAVIORAL_PARAMS)),
        ("temporal", TemporalFeatureTransformer()),
        ("selector", CatBoostFeatureSelector()),
    ])


def create_dl_pipeline(scaler_type: str = "robust") -> Pipeline:
    return Pipeline([
        ("behavior", ClientBehaviorTransformer(**_BEHAVIORAL_PARAMS)),
        ("temporal", TemporalFeatureTransformer()),
        ("onehot", SafeOneHotEncoder()),
        ("selector", DLFeatureSelector()),
        ("scaler", SafeScaler(scaler_type=scaler_type)),
        ("to_float32", ToFloat32Transformer()),
    ])


def build_stateless_pipeline(
    model_type: str,
    scaler_type: str = "robust",
    fitted_params: dict | None = None,
) -> Pipeline:
    if model_type == "catboost":
        return Pipeline([
            ("temporal", TemporalFeatureTransformer()),
            ("selector", CatBoostFeatureSelector()),
        ])

    if model_type == "dl":
        return Pipeline([
            ("temporal", TemporalFeatureTransformer()),
            ("onehot", SafeOneHotEncoder()),
            ("selector", DLFeatureSelector()),
            ("scaler", SafeScaler(scaler_type=scaler_type)),
            ("to_float32", ToFloat32Transformer()),
        ])

    return Pipeline([
        ("temporal", TemporalFeatureTransformer()),
        ("onehot", SafeOneHotEncoder()),
        ("selector", TreeFeatureSelector()),
        ("to_float32", ToFloat32Transformer()),
    ])


def build_full_inference_pipeline(
    model_type: str,
    trained_model,
    scaler_type: str = "robust",
    fitted_params: dict | None = None,
) -> Pipeline:
    stateless = build_stateless_pipeline(model_type, scaler_type, fitted_params)
    return Pipeline(stateless.steps + [("model", trained_model)])


class FraudFeaturePipeline:
    def __init__(self, pipeline: Pipeline, name: str = "unnamed"):
        self.pipeline = pipeline
        self.name = name
        self._lock = threading.RLock()
        self._fitted = False
        self._fit_ts: Optional[str] = None
        self._feature_count: int = 0
        self._transformer_params: dict = {}
        self._behavior_step = None
        self._downstream_pipeline = None

    def __getstate__(self):
        state = self.__dict__.copy()
        state.pop("_lock", None)
        return state

    def __setstate__(self, state):
        self.__dict__.update(state)
        self._lock = threading.RLock()

    def fit(self, X: pd.DataFrame, y=None) -> "FraudFeaturePipeline":
        _validate_input(X, f"{self.name}.fit")
        logger.info("%s.fit — %d rows", self.name, len(X))

        with self._lock:
            steps = list(self.pipeline.steps)

            if not steps:
                raise ValueError(f"{self.name}.fit: pipeline has no steps")

            if steps[0][0] == "behavior":
                self._behavior_step = clone(steps[0][1])
                self._behavior_step.fit(X[REQUIRED_INPUT_COLS], y)

                X_behavior_train = self._behavior_step.transform_train_asof(
                    X[REQUIRED_INPUT_COLS]
                )

                downstream_steps = steps[1:]
                if downstream_steps:
                    self._downstream_pipeline = Pipeline([
                        (step_name, clone(step_obj))
                        for step_name, step_obj in downstream_steps
                    ])
                    self._downstream_pipeline.fit(X_behavior_train, y)
                    transformed = self._downstream_pipeline.transform(X_behavior_train)
                else:
                    self._downstream_pipeline = None
                    transformed = X_behavior_train
            else:
                self._behavior_step = None
                self._downstream_pipeline = clone(self.pipeline)
                self._downstream_pipeline.fit(X[REQUIRED_INPUT_COLS], y)
                transformed = self._downstream_pipeline.transform(X[REQUIRED_INPUT_COLS])

            self._fitted = True
            self._fit_ts = datetime.now(timezone.utc).isoformat()
            self._feature_count = transformed.shape[1] if hasattr(transformed, "shape") else 0
            self._extract_fitted_params()

        logger.info("%s.fit — done", self.name)
        return self

    def _extract_fitted_params(self):
        params = {}
        pipeline_ref = self._downstream_pipeline if self._downstream_pipeline is not None else self.pipeline

        if pipeline_ref is not None and hasattr(pipeline_ref, "named_steps"):
            if "onehot" in pipeline_ref.named_steps:
                ohe = pipeline_ref.named_steps["onehot"]
                if hasattr(ohe, "_encoders"):
                    params["onehot_categories"] = {
                        col: enc.categories_[0].tolist()
                        for col, enc in ohe._encoders.items()
                    }

            if "scaler" in pipeline_ref.named_steps:
                scaler = pipeline_ref.named_steps["scaler"]
                if hasattr(scaler, "_scaler"):
                    s = scaler._scaler
                    if hasattr(s, "center_"):
                        params["scaler_mean"] = s.center_.tolist()
                        params["scaler_scale"] = s.scale_.tolist()
                        params["scaler_type"] = "robust"
                    elif hasattr(s, "mean_"):
                        params["scaler_mean"] = s.mean_.tolist()
                        params["scaler_scale"] = s.scale_.tolist()
                        params["scaler_type"] = "standard"
                    elif hasattr(s, "data_min_"):
                        params["scaler_min"] = s.data_min_.tolist()
                        params["scaler_max"] = s.data_max_.tolist()
                        params["scaler_type"] = "minmax"

            try:
                last_step = pipeline_ref.steps[-1][1]
                if hasattr(last_step, "get_feature_names_out"):
                    params["feature_names"] = list(last_step.get_feature_names_out())
                elif hasattr(last_step, "selected_features_"):
                    params["feature_names"] = list(last_step.selected_features_)
            except Exception:
                pass

        self._transformer_params = params

    def transform_train(self, X: pd.DataFrame) -> pd.DataFrame | np.ndarray:
        if not self._fitted:
            raise RuntimeError(f"{self.name}.transform_train called before fit().")

        _validate_input(X, f"{self.name}.transform_train")
        logger.info("%s.transform_train — %d rows", self.name, len(X))

        with self._lock:
            X_work = X[REQUIRED_INPUT_COLS].copy()

            if self._behavior_step is not None:
                X_work = self._behavior_step.transform_train_asof(X_work)

            if self._downstream_pipeline is not None:
                result = self._downstream_pipeline.transform(X_work)
            else:
                result = X_work

        n_features = result.shape[1] if hasattr(result, "shape") else -1
        self._feature_count = n_features
        logger.info("%s.transform_train — output shape (%d, %d)", self.name, len(X), n_features)
        return result

    def transform(self, X: pd.DataFrame) -> pd.DataFrame | np.ndarray:
        if not self._fitted:
            raise RuntimeError(f"{self.name}.transform called before fit().")

        _validate_input(X, f"{self.name}.transform")
        logger.info("%s.transform — %d rows", self.name, len(X))

        with self._lock:
            X_work = X[REQUIRED_INPUT_COLS].copy()

            if self._behavior_step is not None:
                X_work = self._behavior_step.transform(X_work, use_fitted_history=True)

            if self._downstream_pipeline is not None:
                result = self._downstream_pipeline.transform(X_work)
            else:
                result = X_work

        n_features = result.shape[1] if hasattr(result, "shape") else -1
        self._feature_count = n_features
        logger.info("%s.transform — output shape (%d, %d)", self.name, len(X), n_features)
        return result

    def fit_transform(self, X: pd.DataFrame, y=None) -> pd.DataFrame | np.ndarray:
        self.fit(X, y)
        return self.transform_train(X)

    def save(self, path: str | Path):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)

        fd, tmp_path = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
        try:
            os.close(fd)
            try:
                joblib.dump(self, tmp_path, compress=("lz4", 3))
            except ValueError:
                joblib.dump(self, tmp_path, compress=("gzip", 3))
            os.replace(tmp_path, path)
        except Exception:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)
            raise

        meta = self._build_metadata()
        meta_path = path.with_suffix(".meta.json")
        meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")

        logger.info("Pipeline saved → %s  (meta → %s)", path, meta_path)

    @staticmethod
    def load(path: str | Path) -> "FraudFeaturePipeline":
        path = Path(path)
        if not path.exists():
            raise FileNotFoundError(f"Pipeline file not found: {path}")

        obj = joblib.load(path)
        if not isinstance(obj, FraudFeaturePipeline):
            raise TypeError(f"Expected FraudFeaturePipeline, got {type(obj).__name__}")

        logger.info("Pipeline loaded ← %s", path)
        return obj

    def _build_metadata(self) -> dict:
        feature_names: list[str] = self._transformer_params.get("feature_names", [])

        if self.name.startswith("catboost"):
            pipeline_type = "catboost"
        elif self.name.startswith("dl"):
            pipeline_type = "dl"
        else:
            pipeline_type = "tree"

        pipeline_steps = []
        if self._behavior_step is not None:
            pipeline_steps.append("behavior")
        if self._downstream_pipeline is not None:
            pipeline_steps.extend(name for name, _ in self._downstream_pipeline.steps)

        return {
            "name": self.name,
            "pipeline_type": pipeline_type,
            "fitted_at": self._fit_ts,
            "feature_count": self._feature_count or len(feature_names),
            "feature_names": feature_names,
            "pipeline_steps": pipeline_steps,
            "required_input": REQUIRED_INPUT_COLS,
            "transformer_params": self._transformer_params,
        }

    def __repr__(self) -> str:
        status = "fitted" if self._fitted else "not fitted"
        return f"FraudFeaturePipeline(name='{self.name}', {status})"


def build_all_pipelines(
    df_train: pd.DataFrame,
    df_val: pd.DataFrame,
    df_test: pd.DataFrame,
    output_dir: str | Path | None = None,
) -> dict[str, dict]:
    configs = [
        ("tree", create_tree_pipeline()),
        ("catboost", create_catboost_pipeline()),
        ("dl_robust", create_dl_pipeline("robust")),
        ("dl_standard", create_dl_pipeline("standard")),
    ]

    results = {}
    for name, pipe in configs:
        logger.info("Building pipeline: %s", name.upper())
        print(f"\n{'─'*60}\n  Pipeline: {name.upper()}\n{'─'*60}")

        fp = FraudFeaturePipeline(pipe, name=name)
        fp.fit(df_train)

        X_tr = fp.transform_train(df_train)
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


def _validate_input(X: pd.DataFrame, context: str):
    if not isinstance(X, pd.DataFrame):
        raise TypeError(f"{context}: expected pd.DataFrame, got {type(X).__name__}")

    missing = set(REQUIRED_INPUT_COLS) - set(X.columns)
    if missing:
        raise ValueError(f"{context}: missing required columns: {sorted(missing)}")

    if len(X) == 0:
        raise ValueError(f"{context}: DataFrame is empty")