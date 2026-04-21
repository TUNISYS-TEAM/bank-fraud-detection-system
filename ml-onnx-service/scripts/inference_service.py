"""
CLI entry point for fraud inference with explainability.
Version robuste : crée les colonnes manquantes, mappe les catégories inconnues,
et supporte l'explicabilité SHAP.
"""

import argparse
import json
import sys
import traceback
import hashlib
import numpy as np
import pandas as pd
import joblib
import onnxruntime as ort

sys.path.insert(0, '/app')

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


def main():
    parser = argparse.ArgumentParser(description="Fraud inference with explainability")
    parser.add_argument('--model-path', required=True)
    parser.add_argument('--pipeline-path', required=True)
    parser.add_argument('--input', required=True)
    parser.add_argument('--threshold', type=float, default=0.5)
    parser.add_argument('--enable-shap', action='store_true', help="Enable SHAP explanations")
    parser.add_argument('--background-data', help="Path to background samples .npy file (for SHAP)")
    parser.add_argument('--feature-names', help="Path to feature names .json file (for SHAP)")
    args = parser.parse_args()

    try:
        with open(args.input, 'r') as f:
            input_data = json.load(f)

        # Charger préprocesseur et modèle ONNX
        preprocessor = joblib.load(args.pipeline_path)
        ort_session = ort.InferenceSession(args.model_path)
        input_name = ort_session.get_inputs()[0].name
        expected_dim = ort_session.get_inputs()[0].shape[1]

        # Charger le modèle XGBoost complet pour SHAP (si demandé)
        xgb_model = None
        explainer = None
        if args.enable_shap:
            # Le pipeline complet contient le classifieur XGBoost
            full_pipeline = joblib.load(args.pipeline_path)
            if 'classifier' in full_pipeline.named_steps:
                xgb_model = full_pipeline.named_steps['classifier']
            else:
                print("WARNING: No classifier found in pipeline, SHAP disabled", file=sys.stderr)
                args.enable_shap = False

            if args.enable_shap and args.background_data and args.feature_names:
                try:
                    background = np.load(args.background_data)
                    with open(args.feature_names, 'r') as f:
                        feature_names = json.load(f)
                    explainer = FraudExplainabilityEngine(
                        model=xgb_model,
                        feature_names=feature_names,
                        background_data=background,
                        threshold=args.threshold,
                        config_path=None,
                        dl_model_path=None,
                        dl_input_dim=None
                    )
                    print(f"SHAP explainer initialized with {len(feature_names)} features", file=sys.stderr)
                except Exception as e:
                    print(f"Failed to initialize SHAP explainer: {e}", file=sys.stderr)
                    args.enable_shap = False
            elif args.enable_shap:
                print("WARNING: --background-data and --feature-names required for SHAP", file=sys.stderr)
                args.enable_shap = False

        # -----------------------------------------------------------------
        # 1. Parse batch et ajout de _row_id
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
        # 2. Construire DataFrame de base
        # -----------------------------------------------------------------
        df = pd.DataFrame(transactions_for_df)

        # -----------------------------------------------------------------
        # 3. Créer les colonnes manquantes attendues par le préprocesseur
        # -----------------------------------------------------------------
        if 'device_id' in df.columns:
            df['Device_Id'] = df['device_id'].astype(str).str.extract(r'(\d+)')[0].fillna(0).astype(np.float32)
        else:
            df['Device_Id'] = 0.0

        resolver = get_resolver()
        if 'Customer_Postal_Code' in df.columns:
            df_temp = resolver.enrich_dataframe(df, 'Customer_Postal_Code', add_gov=False, add_deleg=False, add_region=True)
            df['Customer_Region'] = df_temp['region'].astype('category').cat.codes.astype(np.float32)
        else:
            df['Customer_Region'] = 0.0

        if 'Merchant_Postal_Code' in df.columns:
            df_temp = resolver.enrich_dataframe(df, 'Merchant_Postal_Code', add_gov=False, add_deleg=False, add_region=True)
            df['Merchant_Region'] = df_temp['region'].astype('category').cat.codes.astype(np.float32)
        else:
            df['Merchant_Region'] = 0.0

        df['Customer_Region'] = df['Customer_Region'].astype(np.float32)
        df['Merchant_Region'] = df['Merchant_Region'].astype(np.float32)
        df['Device_Id'] = df['Device_Id'].astype(np.float32)

        # -----------------------------------------------------------------
        # 4. Ajouter les colonnes comportementales manquantes
        # -----------------------------------------------------------------
        behavioral_cols = [
            'client_AVG_TX_Amount', 'client_AVG_TX_PerDay', 'client_amount_deviation',
            'client_abs_amount_deviation', 'client_amount_max_ratio', 'client_amount_z_score',
            'client_is_new_max', 'client_is_new_min', 'client_total_transactions',
            'client_time_since_last_tx', 'client_hours_since_last_tx', 'client_total_days_since_first',
            'client_usual_region', 'client_is_unusual_region', 'client_region_freq',
            'client_usual_channel', 'client_is_unusual_channel', 'client_channel_freq',
            'client_usual_category', 'client_is_unusual_category', 'client_category_freq',
            'client_risk_score', 'amount_abs_diff', 'amount_ratio_raw', 'log_total_tx',
            'is_unusual_region_with_history', 'is_unusual_channel_with_history', 'is_unusual_category_with_history'
        ]
        for col in behavioral_cols:
            if col not in df.columns:
                if col in ['client_usual_region', 'client_usual_channel', 'client_usual_category']:
                    df[col] = 'Unknown'
                else:
                    df[col] = 0.0

        numeric_cols = df.select_dtypes(include=[np.number]).columns
        df[numeric_cols] = df[numeric_cols].astype(np.float32)

        # -----------------------------------------------------------------
        # 5. Mapper les catégories inconnues pour Channel et category
        # -----------------------------------------------------------------
        onehot_step = None
        for step in preprocessor.named_steps.values():
            if hasattr(step, '_encoders') and step._encoders:
                onehot_step = step
                break
        if onehot_step is not None:
            for col in ['Channel', 'category']:
                if col in onehot_step._encoders:
                    known_cats = onehot_step._encoders[col].categories_[0]
                    if col in df.columns:
                        df[col] = df[col].apply(lambda x: x if x in known_cats else known_cats[0])

        # -----------------------------------------------------------------
        # 6. Appliquer la transformation
        # -----------------------------------------------------------------
        X_processed = preprocessor.transform(df)
        if hasattr(X_processed, 'toarray'):
            X_processed = X_processed.toarray()
        X_processed = np.asarray(X_processed, dtype=np.float32)

        if X_processed.shape[1] != expected_dim:
            raise RuntimeError(f"Dimension mismatch: got {X_processed.shape[1]}, expected {expected_dim}")

        # -----------------------------------------------------------------
        # 7. Inférence ONNX
        # -----------------------------------------------------------------
        raw_scores = ort_session.run(None, {input_name: X_processed})[0]
        if raw_scores.ndim == 1:
            pass
        elif raw_scores.shape[1] == 2:
            raw_scores = raw_scores[:, 1]
        else:
            raw_scores = raw_scores.flatten()

        if '_row_id' not in df.columns:
            raise RuntimeError("DataFrame does not contain '_row_id' column after preprocessing")
        if not df['_row_id'].is_unique:
            raise RuntimeError("Duplicate _row_id values found")
        if len(raw_scores) != len(df):
            raise RuntimeError(f"Length mismatch: {len(raw_scores)} vs {len(df)}")

        # -----------------------------------------------------------------
        # 8. Mappage scores et features
        # -----------------------------------------------------------------
        score_map = {}
        features_map = {}
        for idx in range(len(df)):
            row_id = int(df['_row_id'].iloc[idx])
            score_map[row_id] = float(raw_scores[idx])
            features_map[row_id] = X_processed[idx]

        # -----------------------------------------------------------------
        # 9. Traitement de chaque transaction
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
            alert_type = "BEHAVIORAL"
            fraud_reason_json = {
                "risk_level": risk,
                "fraud_score": round(fraud_score, 4),
                "summary": f"{risk} : Transaction avec score de fraude {fraud_score:.3f}",
                "contributions": []
            }

            # SHAP explanation if enabled
            if explainer and row_id in features_map:
                try:
                    # Prepare transaction data for explainer
                    transaction_data = {
                        'TX_Amount': tx.get('TX_Amount', tx.get('amount', 0)),
                        'TX_Time': tx.get('TX_Time', tx.get('transaction_date', None)),
                        'Channel': tx.get('Channel', tx.get('channel', 'Unknown')),
                        'category': tx.get('category', tx.get('merchant_category', 'Unknown')),
                        'Customer_Postal_Code': tx.get('Customer_Postal_Code', tx.get('postal_code_client', ''))
                    }
                    # Behavior data already contains computed stats
                    behavior_data = behavior.copy()
                    explanation = explainer.explain_fraud(
                        features=features_map[row_id],
                        fraud_score=fraud_score,
                        transaction_data=transaction_data,
                        behavior_data=behavior_data
                    )
                    fraud_reason_json = explanation['fraud_reason_json']
                    alert_type = explanation.get('alert_type', alert_type)
                except Exception as e:
                    print(f"Explainability failed for row {row_id}: {e}", file=sys.stderr)

            # Behavioral signals (stateless)
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
        # 10. Sortie JSON
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