"""
CLI entry point for fraud inference with explainability.
Production‑grade: _row_id keyed alignment, vectorized casting, deterministic SHAP,
dimension validation, and single‑pass transform (no double transform).
"""

import argparse
import json
import sys
import traceback
import hashlib

import numpy as np
import pandas as pd

sys.path.insert(0, '/app/src')

from shared_ml.inference import FraudInferenceEngine
from shared_ml.explanation import FraudExplainabilityEngine
from shared_ml.utils.postal_codes import get_resolver


def map_risk_level(fraud_score: float, threshold: float) -> str:
    critical_cutoff = threshold + (1.0 - threshold) * 0.5
    if fraud_score >= critical_cutoff:
        return "CRITICAL"
    if fraud_score >= threshold:
        return "HIGH"
    if fraud_score >= threshold * 0.5:
        return "MEDIUM"
    if fraud_score >= threshold * 0.2:
        return "LOW"
    return "VERY_LOW"


def stable_hash(s: str) -> int:
    """Deterministic integer hash (MD5)."""
    return int(hashlib.md5(s.encode()).hexdigest(), 16)


def safe_cast_categorical(df: pd.DataFrame, inference_engine: FraudInferenceEngine) -> pd.DataFrame:
    """
    Convert numeric categorical columns back to string using the fitted encoder's mapping.
    Vectorized for speed.
    """
    preprocessor = inference_engine.preprocessor
    if not hasattr(preprocessor, 'named_steps'):
        return df

    onehot_step = preprocessor.named_steps.get('onehot')
    if onehot_step is None:
        return df

    encoders = getattr(onehot_step, '_encoders', {})
    if not encoders:
        return df

    df_casted = df.copy()
    for col, encoder in encoders.items():
        if col not in df_casted.columns:
            continue
        fitted_categories = encoder.categories_[0]
        if pd.api.types.is_numeric_dtype(df_casted[col]):
            mapping = {i: cat for i, cat in enumerate(fitted_categories)}
            df_casted[col] = df_casted[col].map(mapping).fillna('Unknown')
    return df_casted


def main():
    parser = argparse.ArgumentParser(description="Fraud inference with explainability")
    parser.add_argument('--model-path', required=True)
    parser.add_argument('--pipeline-path', required=True)
    parser.add_argument('--input', required=True)
    parser.add_argument('--threshold', type=float, default=0.5)
    args = parser.parse_args()

    try:
        with open(args.input, 'r') as f:
            input_data = json.load(f)

        inference = FraudInferenceEngine(args.model_path, args.pipeline_path)

        # -----------------------------------------------------------------
        # 1. Parse batch and inject _row_id BEFORE any processing
        # -----------------------------------------------------------------
        batch = input_data.get('batch')
        if batch is None:
            batch = [{'transaction': input_data['transaction'],
                      'existing_behavior': input_data.get('existing_behavior')}]
            is_single = True
        else:
            is_single = False

        tx_map = {}
        behavior_map = {}
        transactions_for_df = []
        for i, item in enumerate(batch):
            tx = item['transaction'].copy()
            tx['_row_id'] = i
            tx_map[i] = tx
            behavior_map[i] = item.get('existing_behavior') or {}
            transactions_for_df.append(tx)

        # -----------------------------------------------------------------
        # 2. Preprocess (engine may drop/reorder rows)
        # -----------------------------------------------------------------
        df = inference.prepare_dataframe(
            transactions_for_df,
            [behavior_map[i] for i in range(len(batch))]
        )

        df = safe_cast_categorical(df, inference)

        # -----------------------------------------------------------------
        # 3. SINGLE TRANSFORM – compute X_processed once, then get scores
        #    directly from the underlying model (eliminates double transform)
        # -----------------------------------------------------------------
        X_processed = inference.preprocessor.transform(df)
        if hasattr(X_processed, 'toarray'):
            X_processed = X_processed.toarray()
        X_processed = np.asarray(X_processed, dtype=np.float32)

        # Get raw scores without re‑transforming
        if hasattr(inference, '_sklearn_model') and inference._sklearn_model is not None:
            raw_scores = inference._sklearn_model.predict_proba(X_processed)[:, 1]
        elif hasattr(inference, '_onnx_session') and inference._onnx_session is not None:
            input_name = inference._onnx_session.get_inputs()[0].name
            raw_scores = inference._onnx_session.run(None, {input_name: X_processed.astype(np.float32)})[0][:, 1]
        else:
            # Fallback (should not be reached)
            raw_scores = inference.predict_scores(df)

        # -----------------------------------------------------------------
        # 4. Validate processed DataFrame
        # -----------------------------------------------------------------
        if '_row_id' not in df.columns:
            raise RuntimeError("prepare_dataframe did not preserve '_row_id' column")
        if not df['_row_id'].is_unique:
            raise RuntimeError("Duplicate _row_id values found – pipeline expansion detected")
        if len(raw_scores) != len(df):
            raise RuntimeError(f"Length mismatch: raw_scores {len(raw_scores)} vs df {len(df)}")

        # -----------------------------------------------------------------
        # 5. Build keyed mappings (row_id -> score, features)
        # -----------------------------------------------------------------
        score_map = {}
        features_map = {}
        for idx in range(len(df)):
            row_id = int(df['_row_id'].iloc[idx])
            score_map[row_id] = float(raw_scores[idx])
            features_map[row_id] = X_processed[idx]

        # -----------------------------------------------------------------
        # 6. Dimension validation
        # -----------------------------------------------------------------
        expected_dim = None
        if hasattr(inference, '_sklearn_model') and inference._sklearn_model is not None:
            expected_dim = inference._sklearn_model.n_features_in_
        elif hasattr(inference, '_onnx_session') and inference._onnx_session is not None:
            input_shape = inference._onnx_session.get_inputs()[0].shape
            if len(input_shape) >= 2:
                expected_dim = input_shape[1]
                if isinstance(expected_dim, str):
                    expected_dim = None
        if expected_dim is not None and X_processed.shape[1] != expected_dim:
            raise ValueError(f"Dimension mismatch: model expects {expected_dim}, got {X_processed.shape[1]}")

        # -----------------------------------------------------------------
        # 7. Explainability engine (SHAP)
        # -----------------------------------------------------------------
        model_for_shap = inference.get_model_for_shap()
        explainer = None
        if model_for_shap is not None:
            n_background = min(100, len(X_processed))
            if len(X_processed) > 1:
                indices = np.linspace(0, len(X_processed) - 1, n_background, dtype=int)
            else:
                indices = np.zeros(n_background, dtype=int)
            background = X_processed[indices]
            explainer = FraudExplainabilityEngine(
                model_for_shap,
                inference.get_feature_names(),
                background_data=background,
                threshold=args.threshold
            )

        resolver = get_resolver()

        # -----------------------------------------------------------------
        # 8. Process each original transaction using _row_id as key
        # -----------------------------------------------------------------
        results = []
        for row_id in range(len(batch)):
            tx = tx_map[row_id]
            behavior = behavior_map[row_id]
            fraud_score = score_map.get(row_id)

            if fraud_score is None:
                results.append({
                    'fraud_score': 0.0,
                    'risk_level': 'UNKNOWN',
                    'alert_type': 'ERROR',
                    'fraud_reason': {'summary': 'Transaction dropped by preprocessing'},
                    'supervised_score': 0.0,
                    'anomaly_score': 0.0,
                    'behavioral_signals': {}
                })
                continue

            risk = map_risk_level(fraud_score, args.threshold)

            # Deterministic SHAP sampling
            tx_id = tx.get('transaction_id', str(row_id))
            use_shap = (explainer is not None and
                        fraud_score >= (args.threshold * 0.5) and
                        (fraud_score >= (args.threshold * 0.8) or (stable_hash(tx_id) % 5 == 0)))

            if use_shap:
                features = features_map[row_id]
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

            # Stateless behavioural signals (read‑only)
            postal_code = tx.get('postal_code_client', tx.get('Customer_Postal_Code', ''))
            client_region = resolver.get_region(postal_code) if postal_code else 'Unknown'

            existing_region = behavior.get('client_usual_region', 'Unknown')
            usual_region = existing_region if existing_region and existing_region != 'Unknown' else client_region
            is_unusual_region = (client_region != usual_region) if (client_region != 'Unknown' and usual_region != 'Unknown') else False
            region_freq = float(behavior.get('client_region_freq', 0.0))

            current_channel = tx.get('Channel', tx.get('channel', 'Unknown'))
            existing_channel = behavior.get('client_usual_channel', 'Unknown')
            usual_channel = existing_channel if existing_channel and existing_channel != 'Unknown' else current_channel
            is_unusual_channel = (current_channel != usual_channel) if (current_channel and current_channel != 'Unknown' and usual_channel != 'Unknown') else False
            channel_freq = float(behavior.get('client_channel_freq', 0.0))

            current_category = tx.get('category', tx.get('merchant_category', 'Unknown'))
            existing_category = behavior.get('client_usual_category', 'Unknown')
            usual_category = existing_category if existing_category and existing_category != 'Unknown' else current_category
            is_unusual_category = (current_category != usual_category) if (current_category and current_category != 'Unknown' and usual_category != 'Unknown') else False
            category_freq = float(behavior.get('client_category_freq', 0.0))

            behavioral_signals = {
                'current_amount': float(tx.get('TX_Amount', tx.get('amount', 0)) or 0),
                'current_avg': float(behavior.get('client_AVG_TX_Amount', 0)),
                'current_total_tx': int(behavior.get('client_total_transactions', 0)),
                'is_first_transaction': int(behavior.get('client_total_transactions', 0)) == 0,
                'client_usual_region': usual_region,
                'client_is_unusual_region': is_unusual_region,
                'client_region_freq': round(region_freq, 4),
                'client_usual_channel': usual_channel,
                'client_is_unusual_channel': is_unusual_channel,
                'client_channel_freq': round(channel_freq, 4),
                'client_usual_category': usual_category,
                'client_is_unusual_category': is_unusual_category,
                'client_category_freq': round(category_freq, 4),
            }

            results.append({
                'fraud_score': fraud_score,
                'risk_level': risk,
                'alert_type': alert_type,
                'fraud_reason': fraud_reason_json,
                'supervised_score': fraud_score,
                'anomaly_score': round(fraud_score * 0.8, 4),
                'behavioral_signals': behavioral_signals,
            })

        # -----------------------------------------------------------------
        # 9. Output JSON
        # -----------------------------------------------------------------
        if is_single:
            print(json.dumps(results[0], default=str, ensure_ascii=False))
        else:
            print(json.dumps({'results': results}, default=str, ensure_ascii=False))

    except Exception as e:
        print(f"ERROR: {str(e)}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        print(json.dumps({'error': str(e)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == '__main__':
    main()