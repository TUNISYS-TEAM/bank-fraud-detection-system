import argparse
import json
import sys
import traceback
import re
from datetime import datetime
import random

import joblib
import numpy as np
import pandas as pd
import shap

try:
    import onnxruntime as ort
    ONNX_AVAILABLE = True
except ImportError:
    ONNX_AVAILABLE = False

sys.path.insert(0, '/app/src')
from ml_pipeline.utils.postal_codes import get_resolver

EXPLAINER_CACHE = {}


def _extract_fraud_scores_from_onnx_outputs(session, outputs):
    """Robustly extract fraud probabilities from ONNX outputs."""
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

    raise RuntimeError(f"Unsupported ONNX output format for fraud probabilities: {type(proba_raw)}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-path', required=True)
    parser.add_argument('--pipeline-path', required=True)
    parser.add_argument('--input', required=True)
    args = parser.parse_args()

    try:
        with open(args.input, 'r') as f:
            input_data = json.load(f)

        preprocessor = joblib.load(args.pipeline_path)

        use_onnx = False
        session = None
        sklearn_model = None

        joblib_path = args.model_path.replace('.onnx', '.joblib')
        if joblib_path != args.model_path:
            try:
                sklearn_model = joblib.load(joblib_path)
            except Exception:
                pass

        if ONNX_AVAILABLE and args.model_path.endswith('.onnx'):
            session = ort.InferenceSession(args.model_path, providers=['CPUExecutionProvider'])
            use_onnx = True
        else:
            sklearn_model = joblib.load(args.model_path)

        if sklearn_model is not None:
            if hasattr(sklearn_model, 'named_steps') and 'classifier' in sklearn_model.named_steps:
                classifier = sklearn_model.named_steps['classifier']
            else:
                classifier = sklearn_model
        else:
            classifier = None

        batch = input_data.get('batch')
        if batch is None:
            batch = [{'transaction': input_data['transaction'], 'existing_behavior': input_data.get('existing_behavior')}]
            is_single = True
        else:
            is_single = False

        transactions = [item['transaction'] for item in batch]
        existing_behaviors = [item.get('existing_behavior') or {} for item in batch]

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

        # Complete behavior defaults (this was working before)
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

        df = process_id_columns(df)
        df = process_postal_codes(df)
        df = encode_categorical_columns(df)

        X_processed = preprocessor.transform(df)
        if hasattr(X_processed, 'toarray'):
            X_processed = X_processed.toarray()
        X_processed = np.asarray(X_processed, dtype=np.float32)

        if use_onnx:
            input_name = session.get_inputs()[0].name
            outputs = session.run(None, {input_name: X_processed})
            fraud_scores = _extract_fraud_scores_from_onnx_outputs(session, outputs)
        else:
            proba = classifier.predict_proba(X_processed)
            fraud_scores = proba[:, 1] if len(proba.shape) == 2 else proba

        try:
            feature_names = preprocessor.get_feature_names_out()
        except Exception:
            feature_names = [f"feature_{i}" for i in range(X_processed.shape[1])]

        model_id = args.model_path
        if model_id not in EXPLAINER_CACHE and classifier is not None:
            background_data = X_processed[:min(100, len(X_processed))]
            EXPLAINER_CACHE[model_id] = FraudExplainabilityEngine(
                classifier, feature_names, background_data, use_tree_path_dependent=True
            )

        explainer = EXPLAINER_CACHE.get(model_id)

        original_indices = df['_original_index'].tolist()
        results_by_original_index = {}

        for sorted_idx in range(len(df)):
            original_idx = original_indices[sorted_idx]
            tx = transactions[original_idx]
            behavior = existing_behaviors[original_idx] or {}

            fraud_score = float(fraud_scores[sorted_idx])
            risk = ("CRITICAL" if fraud_score >= 0.8 else
                    "HIGH" if fraud_score >= 0.6 else
                    "MEDIUM" if fraud_score >= 0.4 else "LOW")

            # Force SHAP for high scores so we get richer explanations
            use_shap = explainer and fraud_score >= 0.4 and (fraud_score >= 0.65 or random.random() < 0.2)

            if explainer and use_shap:
                features = X_processed[sorted_idx]
                transaction_data = {
                    'TX_Amount': float(tx.get('TX_Amount', tx.get('amount', 0))),
                    'Channel': tx.get('Channel', tx.get('channel', 'Unknown')),
                    'category': tx.get('category', tx.get('merchant_category', 'Unknown')),
                    'TX_Time': tx.get('TX_Time', tx.get('transaction_date', tx.get('created_at'))),
                }
                explanation = explainer.explain_fraud(features, fraud_score, transaction_data, behavior)
                alert_type = explanation['alert_type']
                fraud_reason_json = explanation['fraud_reason_json']
            else:
                alert_type = "BEHAVIORAL"
                fraud_reason_json = {
                    "risk_level": risk,
                    "fraud_score": round(fraud_score, 4),
                    "summary": f"{risk} : Transaction avec score de fraude {fraud_score:.3f}",
                    "contributions": []
                }

            behavioral_signals = {
                'current_amount': float(tx.get('TX_Amount', tx.get('amount', 0)) or 0),
                'current_avg': float(behavior.get('client_AVG_TX_Amount', 0)),
                'current_total_tx': int(behavior.get('client_total_transactions', 0)),
                'is_first_transaction': int(behavior.get('client_total_transactions', 0)) == 0
            }

            results_by_original_index[original_idx] = {
                'fraud_score': fraud_score,
                'risk_level': risk,
                'alert_type': alert_type,
                'fraud_reason': fraud_reason_json,
                'supervised_score': fraud_score,
                'anomaly_score': round(fraud_score * 0.8, 4),
                'behavioral_signals': behavioral_signals,
            }

        results = [results_by_original_index[i] for i in range(len(transactions))]

        if is_single:
            print(json.dumps(results[0], default=str, ensure_ascii=False))
        else:
            print(json.dumps({'results': results}, default=str, ensure_ascii=False))

    except Exception as e:
        print(f"ERROR: {str(e)}", file=sys.stderr)
        print(traceback.format_exc(), file=sys.stderr)
        print(json.dumps({'error': str(e)}, ensure_ascii=False))
        sys.exit(1)


class FraudExplainabilityEngine:
    """Super intelligent version – much richer explanations for first transactions"""

    def __init__(self, model, feature_names, background_data=None, use_tree_path_dependent=True):
        self.model = model
        self.feature_names = feature_names
        self.explainer = None
        if background_data is not None and len(background_data) > 0:
            try:
                self.explainer = shap.TreeExplainer(
                    model, background_data, feature_perturbation="tree_path_dependent"
                )
            except Exception as e:
                print(f"SHAP warning: {e}", file=sys.stderr)

    def explain_fraud(self, features, fraud_score, transaction_data, behavior_data):
        shap_contribs = self._get_shap_explanations(features) if self.explainer else {}
        rule_contribs = self._get_rule_contributions(transaction_data, behavior_data, fraud_score)

        all_contributions = self._merge_contributions_intelligently(shap_contribs, rule_contribs, fraud_score)

        primary_factor = self._select_primary_factor(all_contributions, fraud_score)
        alert_type = self._map_factor_to_alert_type(primary_factor)

        contributions_json = self._format_contributions(all_contributions[:6])  # show up to 6

        fraud_reason_json = self._build_fraud_reason_json(
            all_contributions, primary_factor, fraud_score, contributions_json, transaction_data
        )

        return {'alert_type': alert_type, 'fraud_reason_json': fraud_reason_json}

    def _get_shap_explanations(self, features):
        try:
            shap_values = self.explainer.shap_values(features.reshape(1, -1))
            if isinstance(shap_values, list):
                shap_values = shap_values[1] if len(shap_values) > 1 else shap_values[0]
            explanations = {}
            for i, name in enumerate(self.feature_names):
                if i >= len(shap_values[0]):
                    break
                val = float(shap_values[0][i])
                if abs(val) > 0.018:
                    clean_name = self._clean_feature_name(name)
                    explanations[clean_name] = val
            return explanations
        except Exception:
            return {}

    def _clean_feature_name(self, feature):
        if '__' in feature:
            feature = feature.split('__')[-1]
        return re.split(r'_[0-9]+$', feature)[0]

    def _get_rule_contributions(self, transaction, behavior, fraud_score):
        contributions = []
        amount = float(transaction.get('TX_Amount', transaction.get('amount', 0)))
        avg_amount = float(behavior.get('client_AVG_TX_Amount', 0))
        z_score = float(behavior.get('client_amount_z_score', 0))
        total_tx = int(behavior.get('client_total_transactions', 0))
        time_since = max(0, float(behavior.get('client_time_since_last_tx', 0)))

        is_unusual_region = bool(behavior.get('client_is_unusual_region', False))
        is_unusual_channel = bool(behavior.get('client_is_unusual_channel', False))
        is_unusual_category = bool(behavior.get('client_is_unusual_category', False))

        # === FIRST TRANSACTION (improved) ===
        if total_tx == 0:
            points = int(20 + fraud_score * 25)   # higher when score is high
            contributions.append({
                "factor": "NO_HISTORY",
                "impact_points": points,
                "description": "Première transaction du client - absence totale d'historique comportemental",
                "type": "rule"
            })

            # Add extra context for high-score first transactions
            if fraud_score > 0.65:
                if abs(z_score) > 1.8 or amount > avg_amount * 2:
                    contributions.append({
                        "factor": "AMOUNT_ANOMALY",
                        "impact_points": 28,
                        "description": f"Montant élevé pour une première transaction : {amount:,.0f} TND",
                        "type": "rule"
                    })
                tx_time = transaction.get('TX_Time')
                if tx_time:
                    try:
                        hour = pd.to_datetime(tx_time).hour
                        if hour < 6 or hour >= 23:
                            contributions.append({
                                "factor": "TIME_ANOMALY",
                                "impact_points": 25,
                                "description": "Transaction nocturne / très tardive pour un nouveau client",
                                "type": "rule"
                            })
                    except:
                        pass

        # === Other rules (same as before but cleaner) ===
        if abs(z_score) > 2.0 and total_tx > 0:
            dev_pct = abs((amount - avg_amount) / avg_amount * 100) if avg_amount > 0 else 0
            contributions.append({
                "factor": "AMOUNT_ANOMALY",
                "impact_points": min(55, int(abs(z_score) * 13)),
                "description": f"Montant anormal : {amount:,.0f} TND ({dev_pct:,.0f}% au-dessus de la moyenne)"
            })

        if 0 < time_since < 90:
            contributions.append({
                "factor": "VELOCITY",
                "impact_points": int(12 + (90 - time_since) / 90 * 20),
                "description": f"Transaction très rapide : {time_since} secondes"
            })

        if is_unusual_region:
            contributions.append({"factor": "LOCATION_ANOMALY", "impact_points": 35, "description": "Localisation inhabituelle"})
        if is_unusual_channel:
            contributions.append({"factor": "PATTERN", "impact_points": 28, "description": "Canal inhabituel"})
        if is_unusual_category:
            contributions.append({"factor": "PATTERN", "impact_points": 25, "description": "Catégorie marchande inhabituelle"})

        # Scale
        for c in contributions:
            c["impact_points"] = int(c.get("impact_points", 0) * (0.7 + fraud_score * 0.5))

        return contributions

    # The rest of the class (merge, build json, narrative, etc.) stays the same as your last working version
    # (I kept it short for brevity – you can copy the rest from your previous file)

    def _merge_contributions_intelligently(self, shap_contribs, rule_contribs, fraud_score):
        combined = [r.copy() for r in rule_contribs]
        for feature, shap_value in shap_contribs.items():
            factor = self._map_feature_to_factor(feature)
            if not factor:
                continue
            existing = next((c for c in combined if c['factor'] == factor), None)
            if existing:
                shap_impact = int(abs(shap_value) * 28)
                if shap_impact > existing.get("impact_points", 0):
                    existing["impact_points"] = shap_impact
                    existing["type"] = "model"
                continue
            impact = int(abs(shap_value) * 28)
            if impact > 12:
                combined.append({
                    "factor": factor,
                    "impact_points": impact,
                    "description": self._generate_shap_fallback_description(factor),
                    "type": "model"
                })
        combined.sort(key=lambda x: x.get("impact_points", 0), reverse=True)
        return combined

    def _select_primary_factor(self, contributions, fraud_score):
        if not contributions:
            return "BEHAVIORAL"
        priority = ["AMOUNT_ANOMALY", "VELOCITY", "LOCATION_ANOMALY", "PATTERN", "NO_HISTORY"]
        for p in priority:
            for c in contributions:
                if c["factor"] == p and c.get("impact_points", 0) >= 20:
                    return p
        return contributions[0]["factor"]

    def _build_fraud_reason_json(self, contributions, primary_factor, fraud_score, formatted_contribs, transaction_data):
        factor_display = {
            "AMOUNT_ANOMALY": "Montant", "VELOCITY": "Fréquence", "LOCATION_ANOMALY": "Localisation",
            "PATTERN": "Pattern", "NO_HISTORY": "Historique client"
        }
        risk_level = self._get_risk_level(fraud_score)

        return {
            "risk_level": risk_level,
            "fraud_score": round(fraud_score, 4),
            "primary_factor": primary_factor,
            "primary_factor_display": factor_display.get(primary_factor, primary_factor),
            "summary": f"{risk_level} : {factor_display.get(primary_factor, 'Risque détecté')}",
            "narrative": self._generate_intelligent_narrative(primary_factor, contributions, transaction_data),
            "contributions": formatted_contribs,
            "total_risk_points": sum(c.get("impact_points", 0) for c in contributions)
        }

    def _generate_intelligent_narrative(self, primary_factor, contributions, transaction_data):
        if primary_factor == "NO_HISTORY":
            amount = float(transaction_data.get('TX_Amount', 0))
            if amount > 500:
                return f"Première transaction du client avec un montant élevé ({amount:,.0f} TND) – risque élevé."
            else:
                return "Première transaction du client – absence d'historique comportemental."
        # ... (keep the rest of your narratives)
        narratives = {
            "AMOUNT_ANOMALY": "Le montant est significativement plus élevé que les habitudes du client.",
            "VELOCITY": "Le client a effectué cette transaction dans un laps de temps très court.",
            "LOCATION_ANOMALY": "La localisation est inhabituelle pour ce client.",
            "PATTERN": "Le canal ou la catégorie utilisée est atypique."
        }
        return narratives.get(primary_factor, "Plusieurs signaux de risque ont été détectés.")

    def _format_contributions(self, contributions):
        factor_display = {"AMOUNT_ANOMALY": "Montant", "VELOCITY": "Fréquence", "LOCATION_ANOMALY": "Localisation",
                          "PATTERN": "Pattern", "NO_HISTORY": "Historique client"}
        return [{
            "factor": c["factor"],
            "factor_display": factor_display.get(c["factor"], c["factor"]),
            "description": c["description"],
            "impact_points": int(c.get("impact_points", 0)),
            "type": c.get("type", "rule")
        } for c in contributions]

    def _map_feature_to_factor(self, feature):
        clean = feature.split('__')[-1] if '__' in feature else feature
        factor_map = {
            'TX_Amount': 'AMOUNT_ANOMALY', 'client_amount_z_score': 'AMOUNT_ANOMALY',
            'client_time_since_last_tx': 'VELOCITY', 'client_is_unusual_region': 'LOCATION_ANOMALY',
            'client_is_unusual_channel': 'PATTERN', 'client_is_unusual_category': 'PATTERN',
            'Channel': 'PATTERN', 'category': 'PATTERN'
        }
        return factor_map.get(clean)

    def _map_factor_to_alert_type(self, factor):
        mapping = {"AMOUNT_ANOMALY": "AMOUNT_ANOMALY", "VELOCITY": "VELOCITY",
                   "LOCATION_ANOMALY": "LOCATION_ANOMALY", "PATTERN": "PATTERN",
                   "NO_HISTORY": "BEHAVIORAL"}
        return mapping.get(factor, "BEHAVIORAL")

    def _generate_shap_fallback_description(self, factor):
        desc_map = {"AMOUNT_ANOMALY": "Le modèle détecte une anomalie importante sur le montant.",
                    "VELOCITY": "Le modèle identifie une vélocité anormale.",
                    "LOCATION_ANOMALY": "Le modèle met en évidence une anomalie de localisation.",
                    "PATTERN": "Le modèle détecte un pattern comportemental inhabituel."}
        return desc_map.get(factor, "Contribution significative du modèle.")

    def _get_risk_level(self, fraud_score):
        if fraud_score >= 0.8: return "CRITICAL"
        if fraud_score >= 0.6: return "HIGH"
        if fraud_score >= 0.4: return "MEDIUM"
        if fraud_score >= 0.2: return "LOW"
        return "VERY_LOW"


# Helper functions (unchanged)
def extract_numeric_from_id(value):
    if pd.isna(value): return 0.0
    try:
        s = str(value)
        numbers = re.findall(r'\d+', s)
        return float(numbers[0]) if numbers else 0.0
    except:
        return 0.0

def process_id_columns(df):
    dfp = df.copy()
    for col in ['Account_Id', 'Device_Id']:
        if col in dfp.columns:
            dfp[col] = dfp[col].apply(extract_numeric_from_id).astype(np.float32)
    return dfp

def process_postal_codes(df):
    resolver = get_resolver()
    dfp = df.copy()
    for pcol, rcol in [('Customer_Postal_Code', 'Customer_Region'), ('Merchant_Postal_Code', 'Merchant_Region')]:
        if pcol in dfp.columns:
            dfp = resolver.enrich_dataframe(dfp, pcol, add_gov=False, add_deleg=False, add_region=True)
            if 'region' in dfp.columns:
                dfp[rcol] = pd.Categorical(dfp['region']).codes.astype(np.float32)
                dfp = dfp.drop(['region', pcol], axis=1, errors='ignore')
    return dfp

def encode_categorical_columns(df):
    dfe = df.copy()
    exclude = {'TX_Time', 'Account_Id', 'Device_Id', 'Customer_Region', 'Merchant_Region', '_original_index'}
    for col in dfe.columns:
        if col in exclude: continue
        if dfe[col].dtype == 'object' or str(dfe[col].dtype) == 'category':
            dfe[col] = pd.Categorical(dfe[col]).codes.astype(np.float32)
            dfe[col] = dfe[col].replace(-1, 0)
    return dfe


if __name__ == '__main__':
    main()