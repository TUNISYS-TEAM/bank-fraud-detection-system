from __future__ import annotations

import logging
import math
import traceback
from typing import Optional, Dict, List, Any

import joblib
import numpy as np
import pandas as pd

try:
    import onnxruntime as ort
    ONNX_AVAILABLE = True
except ImportError:
    ONNX_AVAILABLE = False

from shared_ml.explanation import FraudExplainabilityEngine

logger = logging.getLogger(__name__)

__all__ = ["FraudInferenceEngine"]


class FraudInferenceEngine:

    MAX_EXPLAIN_PER_BATCH = 50
    EXPLAIN_THRESHOLD = 0.7

    def __init__(self, model_path: str, pipeline_path: str,
                 explainability_config: Optional[Dict] = None):
        self.model_path = model_path
        self.pipeline_path = pipeline_path
        self.explainability_config = explainability_config or {}

        self.preprocessor = joblib.load(pipeline_path)
        self.session: Optional["ort.InferenceSession"] = None
        self.sklearn_model = None
        self.expected_input_dim_: Optional[int] = None

        self.explainability_engine: Optional[FraudExplainabilityEngine] = None
        self.feature_names: List[str] = []
        self.background_data: Optional[np.ndarray] = None

        self._load_model()
        self._init_explainability()

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

    def _init_explainability(self) -> None:
        try:
            self.feature_names = self.get_feature_names()
            shap_model = self.get_model_for_shap()
            threshold = self.explainability_config.get("threshold", 0.5)
            dl_model_path = self.explainability_config.get("dl_model_path")
            dl_input_dim = self.explainability_config.get("dl_input_dim")
            dl_method = self.explainability_config.get("dl_method", "integrated_gradients")
            config_path = self.explainability_config.get("config_path")
            background_path = self.explainability_config.get("background_data_path")

            if background_path:
                try:
                    self.background_data = np.load(background_path)
                except Exception:
                    logger.warning("Could not load background data from %s", background_path)

            self.explainability_engine = FraudExplainabilityEngine(
                model=shap_model,
                feature_names=self.feature_names,
                background_data=self.background_data,
                threshold=threshold,
                config_path=config_path,
                dl_model_path=dl_model_path,
                dl_input_dim=dl_input_dim,
                dl_method=dl_method
            )

            logger.info("FraudExplainabilityEngine initialized | type=%s threshold=%.2f features=%d shap=%s dl=%s",
                        self.explainability_engine.model_type,
                        threshold,
                        len(self.feature_names),
                        "yes" if self.explainability_engine.explainer else "no",
                        "yes" if self.explainability_engine.dl_explainer else "no")

        except Exception:
            logger.error("Failed to initialize explainability engine", exc_info=True)
            self.explainability_engine = None

    def _get_classifier(self):
        if self.sklearn_model is None:
            return None
        if hasattr(self.sklearn_model, "named_steps") and "classifier" in self.sklearn_model.named_steps:
            return self.sklearn_model.named_steps["classifier"]
        return self.sklearn_model

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

    def get_feature_names(self) -> list[str]:
        try:
            names = self.preprocessor.get_feature_names_out()
            return list(names)
        except Exception:
            return []

    def get_threshold(self) -> float:
        return self.explainability_config.get("threshold", 0.5)

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
        X_processed = np.ascontiguousarray(X_processed)

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

    def predict_with_explanation(self, df_raw: pd.DataFrame,
                                  transaction_data: Optional[Dict] = None,
                                  behavior_data: Optional[Dict] = None) -> Dict[str, Any]:
        fraud_score = float(self.predict_scores(df_raw)[0])

        if transaction_data is None:
            transaction_data = {}
        if behavior_data is None:
            behavior_data = {}

        if self.explainability_engine is not None:
            try:
                X_processed = self.transform(df_raw)
                features = X_processed[0]

                result = self.explainability_engine.explain_fraud(
                    features=features,
                    fraud_score=fraud_score,
                    transaction_data=transaction_data,
                    behavior_data=behavior_data
                )

                return {
                    "fraud_score": fraud_score,
                    "risk_level": result["fraud_reason_json"]["risk_level"],
                    "alert_type": result["alert_type"],
                    "fraud_reason": result["fraud_reason_json"],
                    "supervised_score": fraud_score,
                    "anomaly_score": fraud_score * 0.8,
                    "behavioral_signals": behavior_data
                }

            except Exception:
                logger.error("Explainability failed for single transaction", exc_info=True)

        return self._build_minimal_result(fraud_score, behavior_data)

    def predict_batch_with_explanation(self, df_raw: pd.DataFrame,
                                        batch_items: List[Dict]) -> List[Dict]:
        X_processed = self.transform(df_raw)
        fraud_scores = self.predict_scores(df_raw)

        explain_engine = self.explainability_engine
        n = len(fraud_scores)

        if explain_engine is not None:
            high_risk_mask = fraud_scores >= self.EXPLAIN_THRESHOLD
            high_risk_indices = np.where(high_risk_mask)[0]
            if len(high_risk_indices) > self.MAX_EXPLAIN_PER_BATCH:
                top_k = np.argpartition(fraud_scores, -self.MAX_EXPLAIN_PER_BATCH)[-self.MAX_EXPLAIN_PER_BATCH:]
                explain_indices = set(top_k)
            else:
                explain_indices = set(high_risk_indices)
        else:
            explain_indices = set()

        results = []

        for i, item in enumerate(batch_items):
            fraud_score = float(fraud_scores[i])
            transaction_data = item.get("transaction", {})
            behavior_data = item.get("existing_behavior", {})

            if i not in explain_indices:
                results.append(self._build_minimal_result(fraud_score, behavior_data))
                continue

            try:
                features = X_processed[i]

                result = explain_engine.explain_fraud(
                    features=features,
                    fraud_score=fraud_score,
                    transaction_data=transaction_data,
                    behavior_data=behavior_data
                )

                results.append({
                    "fraud_score": fraud_score,
                    "risk_level": result["fraud_reason_json"]["risk_level"],
                    "alert_type": result["alert_type"],
                    "fraud_reason": result["fraud_reason_json"],
                    "supervised_score": fraud_score,
                    "anomaly_score": fraud_score * 0.8,
                    "behavioral_signals": behavior_data
                })

            except Exception:
                logger.error("Explainability failed for batch item %d", i, exc_info=True)
                results.append(self._build_minimal_result(fraud_score, behavior_data))

        return results

    def _build_minimal_result(self, fraud_score: float,
                               behavior_data: Optional[Dict] = None) -> Dict[str, Any]:
        threshold = self.get_threshold()

        if fraud_score >= threshold + (1.0 - threshold) * 0.6:
            risk_level = "CRITICAL"
        elif fraud_score >= threshold:
            risk_level = "HIGH"
        elif fraud_score >= threshold * 0.6:
            risk_level = "MEDIUM"
        elif fraud_score >= threshold * 0.3:
            risk_level = "LOW"
        else:
            risk_level = "VERY_LOW"

        total_tx = behavior_data.get('client_total_transactions', 0) if behavior_data else 0
        alert_type = "NO_HISTORY" if total_tx == 0 else "BEHAVIORAL"

        if risk_level in ("CRITICAL", "HIGH"):
            desc = f"Transaction suspecte (score {fraud_score:.1%})"
            detail = f"Score de fraude élevé ({fraud_score:.1%}). Vérification recommandée."
            severity = "high"
            points = int(fraud_score * 75)
        elif risk_level == "MEDIUM":
            desc = f"Transaction modérément atypique (score {fraud_score:.1%})"
            detail = f"Légers écarts comportementaux détectés (score {fraud_score:.1%})."
            severity = "medium"
            points = int(fraud_score * 55)
        else:
            desc = f"Transaction normale (score {fraud_score:.1%})"
            detail = f"Transaction conforme au profil habituel (score {fraud_score:.1%})."
            severity = "low"
            points = int(fraud_score * 35)

        fraud_reason = {
            "risk_level": risk_level,
            "fraud_score": round(fraud_score, 4),
            "primary_factor": "BEHAVIORAL",
            "primary_factor_display": "Analyse comportementale",
            "summary": f"{risk_level} : {desc}",
            "narrative": detail,
            "client_context": {
                "total_transactions": total_tx,
                "has_history": total_tx > 0,
                "avg_amount": 0,
                "risk_score": 0,
                "is_established": False
            },
            "contributions": [{
                "factor": "BEHAVIORAL",
                "factor_display": "Analyse comportementale",
                "description": desc,
                "detail": detail,
                "impact_points": points,
                "type": "fallback",
                "severity": severity
            }],
            "total_risk_points": points,
            "analysis_source": "minimal_fallback",
            "explainers_used": {
                "shap": None,
                "deep_learning": False,
                "statistical": False,
                "score_based": True
            }
        }

        return {
            "fraud_score": fraud_score,
            "risk_level": risk_level,
            "alert_type": alert_type,
            "fraud_reason": fraud_reason,
            "supervised_score": fraud_score,
            "anomaly_score": fraud_score * 0.8,
            "behavioral_signals": behavior_data if behavior_data else {}
        }