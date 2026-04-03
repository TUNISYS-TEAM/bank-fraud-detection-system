#!/usr/bin/env python3
"""
CLI entry point for fraud inference using the separated inference and XAI engines.
Called by Java PythonInferenceService with arguments:
  --model-path <path>
  --pipeline-path <path>
  --input <json_file>
  --threshold <float>
"""

import argparse
import json
import sys
import traceback
import random

import numpy as np

sys.path.insert(0, '/app/src')

from ml_pipeline.inference_pipeline import FraudInferenceEngine
from ml_pipeline.xai_engine import FraudExplainabilityEngine


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
    parser.add_argument('--model-path', required=True, help="Path to model file (.onnx or .joblib)")
    parser.add_argument('--pipeline-path', required=True, help="Path to preprocessor pipeline")
    parser.add_argument('--input', required=True, help="Path to input JSON file")
    parser.add_argument('--threshold', type=float, default=0.5, help="Fraud threshold")
    args = parser.parse_args()

    try:
        with open(args.input, 'r') as f:
            input_data = json.load(f)

        # 1. Initialize inference engine
        inference = FraudInferenceEngine(args.model_path, args.pipeline_path)

        # 2. Parse input batch
        batch = input_data.get('batch')
        if batch is None:
            batch = [{'transaction': input_data['transaction'],
                      'existing_behavior': input_data.get('existing_behavior')}]
            is_single = True
        else:
            is_single = False

        transactions = [item['transaction'] for item in batch]
        existing_behaviors = [item.get('existing_behavior') or {} for item in batch]

        # 3. Preprocess and get raw scores
        df = inference.prepare_dataframe(transactions, existing_behaviors)
        raw_scores = inference.predict_scores(df)

        # 4. Get feature matrix for explainability
        X_processed = inference.preprocessor.transform(df)
        if hasattr(X_processed, 'toarray'):
            X_processed = X_processed.toarray()
        X_processed = np.asarray(X_processed, dtype=np.float32)

        # 5. Build explainability engine (if model supports SHAP)
        model_for_shap = inference.get_model_for_shap()
        explainer = None
        if model_for_shap is not None:
            background = X_processed[:min(100, len(X_processed))]
            explainer = FraudExplainabilityEngine(
                model_for_shap,
                inference.get_feature_names(),
                background_data=background,
                threshold=args.threshold
            )

        # 6. Process each transaction
        results = []
        original_indices = df['_original_index'].tolist()

        for sorted_idx in range(len(df)):
            original_idx = original_indices[sorted_idx]
            tx = transactions[original_idx]
            behavior = existing_behaviors[original_idx] or {}
            fraud_score = float(raw_scores[sorted_idx])

            risk = map_risk_level(fraud_score, args.threshold)

            # Decide whether to use SHAP explanation (same logic as original)
            use_shap = (explainer is not None and
                        fraud_score >= (args.threshold * 0.5) and
                        (fraud_score >= (args.threshold * 0.8) or random.random() < 0.2))

            if use_shap:
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

            results.append({
                'fraud_score': fraud_score,
                'risk_level': risk,
                'alert_type': alert_type,
                'fraud_reason': fraud_reason_json,
                'supervised_score': fraud_score,
                'anomaly_score': round(fraud_score * 0.8, 4),
                'behavioral_signals': behavioral_signals,
            })

        # 7. Output JSON
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