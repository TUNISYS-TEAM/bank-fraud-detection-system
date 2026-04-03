"""
Pure inference service – model loading, feature extraction, scoring.
No business logic, no rules, no explainability.
"""

import re
import math
from datetime import datetime

import joblib
import numpy as np
import pandas as pd

try:
    import onnxruntime as ort
    ONNX_AVAILABLE = True
except ImportError:
    ONNX_AVAILABLE = False

from ml_pipeline.utils.postal_codes import get_resolver

__all__ = ['FraudInferenceEngine']


class FraudInferenceEngine:
    """Handles model loading, feature extraction, and raw prediction."""

    def __init__(self, model_path: str, pipeline_path: str):
        self.model_path = model_path
        self.pipeline_path = pipeline_path
        self.preprocessor = joblib.load(pipeline_path)
        self.session = None          # ONNX runtime session
        self.sklearn_model = None    # joblib model (if not ONNX)
        self._load_model()

    def _load_model(self):
        joblib_path = self.model_path.replace('.onnx', '.joblib')
        if joblib_path != self.model_path:
            try:
                self.sklearn_model = joblib.load(joblib_path)
            except Exception:
                self.sklearn_model = None

        if ONNX_AVAILABLE and self.model_path.endswith('.onnx'):
            self.session = ort.InferenceSession(self.model_path, providers=['CPUExecutionProvider'])
        else:
            self.sklearn_model = joblib.load(self.model_path)

    def _get_classifier(self):
        """Extract classifier from pipeline (if wrapped)."""
        if self.sklearn_model is None:
            return None
        if hasattr(self.sklearn_model, 'named_steps') and 'classifier' in self.sklearn_model.named_steps:
            return self.sklearn_model.named_steps['classifier']
        return self.sklearn_model

    def _extract_fraud_scores_from_onnx_outputs(self, outputs):
        """Robustly extract fraud probabilities from ONNX outputs."""
        session = self.session
        output_names = [o.name.lower() for o in session.get_outputs()]
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
            raise RuntimeError("Could not identify probability output from ONNX session outputs")

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
            if len(proba_raw) == 0:
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

    def _apply_sigmoid_calibration(self, score, a, b):
        z = a * float(score) + b
        z = max(min(z, 500.0), -500.0)
        return 1.0 / (1.0 + math.exp(z))

    def _get_calibration_sigmoid(self):
        calibration = getattr(self.preprocessor, 'calibration_sigmoid', None)
        if calibration is None and self.sklearn_model is not None:
            calibration = getattr(self.sklearn_model, 'calibration_sigmoid', None)
        return calibration

    def prepare_dataframe(self, transactions, existing_behaviors):
        """Convert raw transactions + behaviors into a preprocessed DataFrame."""
        df = pd.DataFrame(transactions)
        df['_original_index'] = range(len(df))

        column_mapping = {
            'created_at': 'TX_Time', 'transaction_date': 'TX_Time',
            'amount': 'TX_Amount', 'channel': 'Channel',
            'merchant_category': 'category',
            'postal_code_client': 'Customer_Postal_Code',
            'postal_code_merchant': 'Merchant_Postal_Code',
            'device_id': 'Device_Id', 'ip_address': 'ip_adress',
            'currency': 'currency', 'account_id': 'Account_Id'
        }
        for old_name, new_name in column_mapping.items():
            if old_name in df.columns and new_name not in df.columns:
                df[new_name] = df[old_name]

        if 'TX_Time' in df.columns:
            df['TX_Time'] = pd.to_datetime(df['TX_Time'], errors='coerce')
        if 'Account_Id' in df.columns:
            df = df.sort_values(['Account_Id', 'TX_Time'], na_position='first')

        behavior_defaults = {
            'client_AVG_TX_Amount': 0.0, 'client_AVG_TX_PerDay': 0.0,
            'client_amount_deviation': 0.0, 'client_abs_amount_deviation': 0.0,
            'client_amount_max_ratio': 1.0, 'client_amount_z_score': 0.0,
            'client_is_new_max': 0, 'client_is_new_min': 0,
            'client_total_transactions': 0, 'client_time_since_last_tx': 0,
            'client_hours_since_last_tx': 0.0, 'client_total_days_since_first': 0.0,
            'client_usual_region': 'Unknown', 'client_is_unusual_region': 0,
            'client_region_freq': 0.0, 'client_usual_channel': 'Unknown',
            'client_is_unusual_channel': 0, 'client_channel_freq': 0.0,
            'client_usual_category': 'Unknown', 'client_is_unusual_category': 0,
            'client_category_freq': 0.0, 'client_risk_score': 0.0,
        }
        for key, default_value in behavior_defaults.items():
            if key not in df.columns:
                df[key] = default_value

        for i, behavior in enumerate(existing_behaviors):
            if behavior:
                for key, value in behavior.items():
                    if key in df.columns:
                        df.at[i, key] = value

        required_cols = ['TX_Amount', 'TX_Time', 'Channel', 'category',
                         'Customer_Postal_Code', 'Merchant_Postal_Code',
                         'Device_Id', 'ip_adress', 'currency', 'Account_Id']
        for col in required_cols:
            if col not in df.columns:
                df[col] = 0.0 if col == 'TX_Amount' else datetime.now() if col == 'TX_Time' else 'Unknown'

        df['TX_Amount'] = pd.to_numeric(df['TX_Amount'], errors='coerce').fillna(0)

        # Apply all transformations (exactly as in the monolithic script)
        df = self._process_id_columns(df)
        df = self._process_postal_codes(df)
        df = self._encode_categorical_columns(df)

        return df

    def _process_id_columns(self, df):
        def extract_numeric_from_id(value):
            if pd.isna(value):
                return 0.0
            try:
                s = str(value)
                numbers = re.findall(r'\d+', s)
                return float(numbers[0]) if numbers else 0.0
            except Exception:
                return 0.0

        dfp = df.copy()
        for col in ['Account_Id', 'Device_Id']:
            if col in dfp.columns:
                dfp[col] = dfp[col].apply(extract_numeric_from_id).astype(np.float32)
        return dfp

    def _process_postal_codes(self, df):
        resolver = get_resolver()
        dfp = df.copy()
        for pcol, rcol in [('Customer_Postal_Code', 'Customer_Region'), ('Merchant_Postal_Code', 'Merchant_Region')]:
            if pcol in dfp.columns:
                dfp = resolver.enrich_dataframe(dfp, pcol, add_gov=False, add_deleg=False, add_region=True)
                if 'region' in dfp.columns:
                    dfp[rcol] = pd.Categorical(dfp['region']).codes.astype(np.float32)
                    dfp = dfp.drop(['region', pcol], axis=1, errors='ignore')
        return dfp

    def _encode_categorical_columns(self, df):
        dfe = df.copy()
        exclude = {'TX_Time', 'Account_Id', 'Device_Id', 'Customer_Region', 'Merchant_Region', '_original_index'}
        for col in dfe.columns:
            if col in exclude:
                continue
            if dfe[col].dtype == 'object' or str(dfe[col].dtype) == 'category':
                dfe[col] = pd.Categorical(dfe[col]).codes.astype(np.float32)
                dfe[col] = dfe[col].replace(-1, 0)
        return dfe

    def predict_scores(self, df):
        """Run model inference on a prepared DataFrame and return raw fraud scores."""
        X_processed = self.preprocessor.transform(df)
        if hasattr(X_processed, 'toarray'):
            X_processed = X_processed.toarray()
        X_processed = np.asarray(X_processed, dtype=np.float32)

        if self.session:
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
                raise RuntimeError("No sklearn classifier available")
            proba = classifier.predict_proba(X_processed)
            fraud_scores = proba[:, 1] if len(proba.shape) == 2 else proba

        return np.clip(fraud_scores, 0.0, 1.0)

    def get_feature_names(self):
        try:
            return self.preprocessor.get_feature_names_out()
        except Exception:
            return [f"feature_{i}" for i in range(100)]

    def get_model_for_shap(self):
        """Return the underlying model suitable for SHAP (unwrapping calibration etc.)."""
        if self.sklearn_model is None:
            return None
        classifier = self.sklearn_model
        if hasattr(self.sklearn_model, 'named_steps') and 'classifier' in self.sklearn_model.named_steps:
            classifier = self.sklearn_model.named_steps['classifier']
        calibrated_classifiers = getattr(classifier, 'calibrated_classifiers_', None)
        if calibrated_classifiers:
            first = calibrated_classifiers[0]
            if hasattr(first, 'estimator'):
                return first.estimator
            if hasattr(first, 'base_estimator'):
                return first.base_estimator
        if hasattr(classifier, 'base_estimator'):
            return classifier.base_estimator
        return classifier