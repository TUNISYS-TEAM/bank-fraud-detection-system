"""
CLI entry point for fraud inference with explainability.
Thin wrapper around shared_ml.inference.FraudInferenceEngine.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import traceback

import numpy as np
import pandas as pd

sys.path.insert(0, "/app")

from shared_ml.explanation import FraudExplainabilityEngine
from shared_ml.inference.inference_pipeline import FraudInferenceEngine
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


def _extract_numeric_id(value) -> float:
    if pd.isna(value):
        return 0.0
    if isinstance(value, (int, float, np.integer, np.floating)):
        return float(value)

    s = str(value).strip()
    match = re.search(r"(\d+)", s)
    if match:
        try:
            return float(match.group(1))
        except Exception:
            return 0.0
    return 0.0


def build_input_dataframe(batch: list[dict]) -> tuple[pd.DataFrame, dict, dict]:
    tx_map = {}
    behavior_map = {}
    rows = []

    for i, item in enumerate(batch):
        tx = item["transaction"].copy()
        tx["_row_id"] = i
        tx_map[i] = tx
        behavior_map[i] = item.get("existing_behavior") or {}
        rows.append(tx)

    df = pd.DataFrame(rows)
    resolver = get_resolver()

    # -----------------------------------------------------------------
    # IDs
    # -----------------------------------------------------------------
    if "device_id" in df.columns and "Device_Id" not in df.columns:
        df["Device_Id"] = df["device_id"].apply(_extract_numeric_id).astype(np.float32)
    elif "Device_Id" in df.columns:
        df["Device_Id"] = df["Device_Id"].apply(_extract_numeric_id).astype(np.float32)
    else:
        df["Device_Id"] = np.float32(0.0)

    if "account_id" in df.columns and "Account_Id" not in df.columns:
        df["Account_Id"] = df["account_id"].apply(_extract_numeric_id).astype(np.float32)
    elif "Account_Id" in df.columns:
        df["Account_Id"] = df["Account_Id"].apply(_extract_numeric_id).astype(np.float32)
    else:
        df["Account_Id"] = np.float32(0.0)

    # -----------------------------------------------------------------
    # Regions - keep compatibility with downstream preprocessing
    # -----------------------------------------------------------------
    if "Customer_Postal_Code" in df.columns:
        df_temp = resolver.enrich_dataframe(
            df.copy(),
            "Customer_Postal_Code",
            add_gov=False,
            add_deleg=False,
            add_region=True,
        )
        if "region" in df_temp.columns:
            df["Customer_Region"] = (
                df_temp["region"].fillna("Unknown").astype("category").cat.codes.astype(np.float32)
            )
        else:
            df["Customer_Region"] = np.float32(0.0)
    else:
        df["Customer_Region"] = np.float32(0.0)

    if "Merchant_Postal_Code" in df.columns:
        df_temp = resolver.enrich_dataframe(
            df.copy(),
            "Merchant_Postal_Code",
            add_gov=False,
            add_deleg=False,
            add_region=True,
        )
        if "region" in df_temp.columns:
            df["Merchant_Region"] = (
                df_temp["region"].fillna("Unknown").astype("category").cat.codes.astype(np.float32)
            )
        else:
            df["Merchant_Region"] = np.float32(0.0)
    else:
        df["Merchant_Region"] = np.float32(0.0)

    df["Customer_Region"] = df["Customer_Region"].astype(np.float32)
    df["Merchant_Region"] = df["Merchant_Region"].astype(np.float32)
    df["Device_Id"] = df["Device_Id"].astype(np.float32)
    df["Account_Id"] = df["Account_Id"].astype(np.float32)

    # -----------------------------------------------------------------
    # Behavioral defaults expected by preprocessing
    # -----------------------------------------------------------------
    behavioral_defaults = {
        "client_AVG_TX_Amount": 0.0,
        "client_AVG_TX_PerDay": 0.0,
        "client_amount_deviation": 0.0,
        "client_abs_amount_deviation": 0.0,
        "client_amount_max_ratio": 0.0,
        "client_amount_z_score": 0.0,
        "client_is_new_max": 0.0,
        "client_is_new_min": 0.0,
        "client_total_transactions": 0.0,
        "client_time_since_last_tx": 0.0,
        "client_hours_since_last_tx": 0.0,
        "client_total_days_since_first": 0.0,
        "client_usual_region": "Unknown",
        "client_is_unusual_region": 0.0,
        "client_region_freq": 0.0,
        "client_usual_channel": "Unknown",
        "client_is_unusual_channel": 0.0,
        "client_channel_freq": 0.0,
        "client_usual_category": "Unknown",
        "client_is_unusual_category": 0.0,
        "client_category_freq": 0.0,
        "client_gap_z_score": 0.0,
        "client_risk_score": 0.0,
    }

    for col, default_value in behavioral_defaults.items():
        if col not in df.columns:
            df[col] = default_value

    # -----------------------------------------------------------------
    # Numeric casting
    # -----------------------------------------------------------------
    numeric_cols = df.select_dtypes(include=[np.number]).columns
    df[numeric_cols] = df[numeric_cols].astype(np.float32)

    return df, tx_map, behavior_map


def main():
    parser = argparse.ArgumentParser(description="Fraud inference with explainability")
    parser.add_argument("--model-path", required=True)
    parser.add_argument("--pipeline-path", required=True)
    parser.add_argument("--input", required=True)
    parser.add_argument("--threshold", type=float, default=0.5)
    parser.add_argument("--enable-shap", action="store_true")
    parser.add_argument("--background-data")
    parser.add_argument("--feature-names")
    args = parser.parse_args()

    try:
        with open(args.input, "r", encoding="utf-8") as f:
            input_data = json.load(f)

        batch = input_data.get("batch")
        if batch is None:
            batch = [{
                "transaction": input_data["transaction"],
                "existing_behavior": input_data.get("existing_behavior"),
            }]
            is_single = True
        else:
            is_single = False

        df, tx_map, behavior_map = build_input_dataframe(batch)

        engine = FraudInferenceEngine(
            model_path=args.model_path,
            pipeline_path=args.pipeline_path,
        )

        fraud_scores = engine.predict_scores(df)

        transformed_features = None
        explainer = None

        if args.enable_shap:
            if hasattr(engine, "transform"):
                try:
                    transformed_features = engine.transform(df)
                except Exception as e:
                    print(f"Transform step failed, SHAP disabled: {e}", file=sys.stderr)
                    transformed_features = None

            shap_model = None
            if hasattr(engine, "get_model_for_shap"):
                try:
                    shap_model = engine.get_model_for_shap()
                except Exception as e:
                    print(f"Could not get model for SHAP: {e}", file=sys.stderr)

            if (
                shap_model is not None
                and transformed_features is not None
                and args.background_data
                and args.feature_names
            ):
                try:
                    background = np.load(args.background_data)
                    with open(args.feature_names, "r", encoding="utf-8") as f:
                        feature_names = json.load(f)

                    explainer = FraudExplainabilityEngine(
                        model=shap_model,
                        feature_names=feature_names,
                        background_data=background,
                        threshold=args.threshold,
                        config_path=None,
                        dl_model_path=None,
                        dl_input_dim=None,
                    )
                except Exception as e:
                    print(f"Failed to initialize SHAP explainer: {e}", file=sys.stderr)

        resolver = get_resolver()
        results = []

        for row_id in range(len(batch)):
            tx = tx_map[row_id]
            behavior = behavior_map[row_id]
            fraud_score = float(fraud_scores[row_id])

            risk = map_risk_level(fraud_score, args.threshold)
            alert_type = "BEHAVIORAL"
            fraud_reason_json = {
                "risk_level": risk,
                "fraud_score": round(fraud_score, 4),
                "summary": f"{risk} : Transaction avec score de fraude {fraud_score:.3f}",
                "contributions": [],
            }

            if explainer is not None and transformed_features is not None:
                try:
                    transaction_data = {
                        "TX_Amount": tx.get("TX_Amount", tx.get("amount", 0)),
                        "TX_Time": tx.get("TX_Time", tx.get("transaction_date")),
                        "Channel": tx.get("Channel", tx.get("channel", "Unknown")),
                        "category": tx.get("category", tx.get("merchant_category", "Unknown")),
                        "Customer_Postal_Code": tx.get(
                            "Customer_Postal_Code",
                            tx.get("postal_code_client", "")
                        ),
                    }
                    explanation = explainer.explain_fraud(
                        features=transformed_features[row_id],
                        fraud_score=fraud_score,
                        transaction_data=transaction_data,
                        behavior_data=behavior.copy(),
                    )
                    fraud_reason_json = explanation["fraud_reason_json"]
                    alert_type = explanation.get("alert_type", alert_type)
                except Exception as e:
                    print(f"Explainability failed for row {row_id}: {e}", file=sys.stderr)

            postal_code = tx.get("postal_code_client", tx.get("Customer_Postal_Code", ""))
            client_region = resolver.get_region(postal_code) if postal_code else "Unknown"

            existing_region = behavior.get("client_usual_region", "Unknown")
            usual_region = existing_region if existing_region and existing_region != "Unknown" else client_region
            is_unusual_region = (
                (client_region != usual_region)
                if (client_region != "Unknown" and usual_region != "Unknown")
                else False
            )

            current_channel = tx.get("Channel", tx.get("channel", "Unknown"))
            existing_channel = behavior.get("client_usual_channel", "Unknown")
            usual_channel = existing_channel if existing_channel and existing_channel != "Unknown" else current_channel
            is_unusual_channel = (
                (current_channel != usual_channel)
                if (current_channel != "Unknown" and usual_channel != "Unknown")
                else False
            )

            current_category = tx.get("category", tx.get("merchant_category", "Unknown"))
            existing_category = behavior.get("client_usual_category", "Unknown")
            usual_category = existing_category if existing_category and existing_category != "Unknown" else current_category
            is_unusual_category = (
                (current_category != usual_category)
                if (current_category != "Unknown" and usual_category != "Unknown")
                else False
            )

            behavioral_signals = {
                "current_amount": float(tx.get("TX_Amount", tx.get("amount", 0)) or 0),
                "current_avg": float(behavior.get("client_AVG_TX_Amount", 0)),
                "current_total_tx": int(behavior.get("client_total_transactions", 0)),
                "is_first_transaction": int(behavior.get("client_total_transactions", 0)) == 0,
                "client_usual_region": usual_region,
                "client_is_unusual_region": is_unusual_region,
                "client_region_freq": round(float(behavior.get("client_region_freq", 0.0)), 4),
                "client_usual_channel": usual_channel,
                "client_is_unusual_channel": is_unusual_channel,
                "client_channel_freq": round(float(behavior.get("client_channel_freq", 0.0)), 4),
                "client_usual_category": usual_category,
                "client_is_unusual_category": is_unusual_category,
                "client_category_freq": round(float(behavior.get("client_category_freq", 0.0)), 4),
            }

            results.append({
                "fraud_score": fraud_score,
                "risk_level": risk,
                "alert_type": alert_type,
                "fraud_reason": fraud_reason_json,
                "supervised_score": fraud_score,
                "anomaly_score": round(fraud_score * 0.8, 4),
                "behavioral_signals": behavioral_signals,
            })

        if is_single:
            print(json.dumps(results[0], default=str, ensure_ascii=False))
        else:
            print(json.dumps({"results": results}, default=str, ensure_ascii=False))

    except Exception as e:
        print(f"ERROR: {str(e)}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == "__main__":
    main()