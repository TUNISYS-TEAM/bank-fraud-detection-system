"""
Pure inference service – model loading, feature extraction, scoring.
Uses a saved preprocessing pipeline + a separate model artifact.
"""

from __future__ import annotations

import math
from typing import Optional

import joblib
import numpy as np
import pandas as pd

try:
    import onnxruntime as ort
    ONNX_AVAILABLE = True
except ImportError:
    ONNX_AVAILABLE = False

__all__ = ["FraudInferenceEngine"]


class FraudInferenceEngine:
    """
    Handles preprocessing + model inference.

    Contract:
    - pipeline_path: sklearn-compatible transformer pipeline
    - model_path: .onnx or .joblib classifier artifact
    """

    def __init__(self, model_path: str, pipeline_path: str):
        self.model_path = model_path
        self.pipeline_path = pipeline_path

        self.preprocessor = joblib.load(pipeline_path)
        self.session: Optional["ort.InferenceSession"] = None
        self.sklearn_model = None
        self.expected_input_dim_: Optional[int] = None

        self._load_model()

    def _load_model(self) -> None:
        if self.model_path.endswith(".onnx"):
            if not ONNX_AVAILABLE:
                raise RuntimeError("onnxruntime is not available but an ONNX model was provided")

            self.session = ort.InferenceSession(
                self.model_path,
                providers=["CPUExecutionProvider"]
            )

            input_shape = self.session.get_inputs()[0].shape
            if len(input_shape) >= 2 and isinstance(input_shape[1], int):
                self.expected_input_dim_ = input_shape[1]

            joblib_path = self.model_path[:-5] + ".joblib"
            try:
                self.sklearn_model = joblib.load(joblib_path)
            except Exception:
                self.sklearn_model = None

        else:
            self.sklearn_model = joblib.load(self.model_path)

    def _get_classifier(self):
        if self.sklearn_model is None:
            return None

        if hasattr(self.sklearn_model, "named_steps") and "classifier" in self.sklearn_model.named_steps:
            return self.sklearn_model.named_steps["classifier"]

        return self.sklearn_model

    def _extract_fraud_scores_from_onnx_outputs(self, outputs) -> np.ndarray:
        output_names = [o.name.lower() for o in self.session.get_outputs()]
        candidate_indices = []

        for idx, name in enumerate(output_names):
            if any(token in name for token in ["prob", "probab", "probabilities", "output_probability"]):
                candidate_indices.append(idx)

        if not candidate_indices:
            for idx, out in enumerate(outputs):
                arr = np.asarray(out)
                if arr.ndim == 2 and arr.shape[1] >= 2:
                    candidate_indices.append(idx)

        if not candidate_indices:
            for idx, out in enumerate(outputs):
                arr = np.asarray(out)
                if arr.ndim >= 1 and np.issubdtype(arr.dtype, np.number):
                    candidate_indices.append(idx)

        if not candidate_indices:
            raise RuntimeError("Could not identify probability output from ONNX outputs")

        proba_raw = outputs[candidate_indices[0]]

        if isinstance(proba_raw, np.ndarray):
            if proba_raw.ndim == 2:
                return proba_raw[:, 1].astype(np.float32) if proba_raw.shape[1] > 1 else proba_raw[:, 0].astype(np.float32)
            if proba_raw.ndim == 1:
                arr = proba_raw.astype(np.float32)
                unique_vals = np.unique(arr)
                if np.all(np.isin(unique_vals, [0, 1])):
                    for idx, out in enumerate(outputs):
                        if idx == candidate_indices[0]:
                            continue
                        alt = np.asarray(out)
                        if alt.ndim == 2 and alt.shape[1] > 1:
                            return alt[:, 1].astype(np.float32)
                        if alt.ndim == 1 and not np.all(np.isin(np.unique(alt), [0, 1])):
                            return alt.astype(np.float32)
                return arr

        if isinstance(proba_raw, list):
            if not proba_raw:
                raise RuntimeError("Empty ONNX probability output list")

            if isinstance(proba_raw[0], dict):
                scores = []
                for item in proba_raw:
                    if 1 in item:
                        scores.append(float(item[1]))
                    elif "1" in item:
                        scores.append(float(item["1"]))
                    else:
                        values = list(item.values())
                        scores.append(float(values[1]) if len(values) >= 2 else float(values[0]) if values else 0.0)
                return np.asarray(scores, dtype=np.float32)

            arr = np.asarray(proba_raw)
            if arr.ndim == 2:
                return arr[:, 1].astype(np.float32) if arr.shape[1] > 1 else arr[:, 0].astype(np.float32)
            if arr.ndim == 1:
                return arr.astype(np.float32)

        raise RuntimeError(f"Unsupported ONNX output format: {type(proba_raw)}")

    def _apply_sigmoid_calibration(self, score: float, a: float, b: float) -> float:
        z = a * float(score) + b
        z = max(min(z, 500.0), -500.0)
        return 1.0 / (1.0 + math.exp(z))

    def _get_calibration_sigmoid(self):
        calibration = getattr(self.preprocessor, "calibration_sigmoid", None)
        if calibration is None and self.sklearn_model is not None:
            calibration = getattr(self.sklearn_model, "calibration_sigmoid", None)
        return calibration

    def transform(self, df_raw: pd.DataFrame) -> np.ndarray:
        X_processed = self.preprocessor.transform(df_raw)

        if hasattr(X_processed, "toarray"):
            X_processed = X_processed.toarray()

        X_processed = np.asarray(X_processed, dtype=np.float32)

        if self.expected_input_dim_ is not None and X_processed.shape[1] != self.expected_input_dim_:
            raise RuntimeError(
                f"Feature dimension mismatch: got {X_processed.shape[1]}, expected {self.expected_input_dim_}"
            )

        return X_processed

    def predict_scores(self, df_raw: pd.DataFrame) -> np.ndarray:
        X_processed = self.transform(df_raw)

        if self.session is not None:
            input_name = self.session.get_inputs()[0].name
            outputs = self.session.run(None, {input_name: X_processed})
            fraud_scores = self._extract_fraud_scores_from_onnx_outputs(outputs)

            calibration = self._get_calibration_sigmoid()
            if calibration and "a" in calibration and "b" in calibration:
                a = float(calibration["a"])
                b = float(calibration["b"])
                fraud_scores = np.asarray(
                    [self._apply_sigmoid_calibration(s, a, b) for s in fraud_scores],
                    dtype=np.float32
                )
        else:
            classifier = self._get_classifier()
            if classifier is None:
                raise RuntimeError("No sklearn classifier available for inference")

            proba = classifier.predict_proba(X_processed)
            fraud_scores = proba[:, 1] if proba.ndim == 2 else proba

        return np.clip(np.asarray(fraud_scores, dtype=np.float32), 0.0, 1.0)

    def get_feature_names(self) -> list[str]:
        try:
            names = self.preprocessor.get_feature_names_out()
            return list(names)
        except Exception:
            return []

    def get_model_for_shap(self):
        if self.sklearn_model is None:
            return None

        classifier = self.sklearn_model
        if hasattr(self.sklearn_model, "named_steps") and "classifier" in self.sklearn_model.named_steps:
            classifier = self.sklearn_model.named_steps["classifier"]

        calibrated_classifiers = getattr(classifier, "calibrated_classifiers_", None)
        if calibrated_classifiers:
            first = calibrated_classifiers[0]
            if hasattr(first, "estimator"):
                return first.estimator
            if hasattr(first, "base_estimator"):
                return first.base_estimator

        if hasattr(classifier, "base_estimator"):
            return classifier.base_estimator

        return classifier