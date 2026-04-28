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

from shared_ml.inference.inference_pipeline import FraudInferenceEngine
from shared_ml.utils.postal_codes import get_resolver


# Mapping from French to English risk levels (fix for Java compatibility)
_RISK_LEVEL_MAP = {
    "CRITIQUE": "CRITICAL",
    "ÉLEVÉ": "HIGH",
    "ELEVE": "HIGH",
    "MOYEN": "MEDIUM",
    "FAIBLE": "LOW",
    "TRÈS FAIBLE": "VERY_LOW",
    "TRES FAIBLE": "VERY_LOW",
}


def _normalize_risk_level(level: str) -> str:
    """Convert French risk levels to English for Java compatibility."""
    if level is None:
        return "MEDIUM"
    upper = level.upper().strip()
    # Remove accents for comparison
    normalized = upper.replace("É", "E").replace("È", "E").replace("Ê", "E")
    for french, english in _RISK_LEVEL_MAP.items():
        if normalized == french.replace("É", "E").replace("È", "E"):
            return english
    # If already English, return as-is
    if upper in ("CRITICAL", "HIGH", "MEDIUM", "LOW", "VERY_LOW"):
        return upper
    return "MEDIUM"


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

    # IDs
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

    # Regions
    if "Customer_Postal_Code" in df.columns:
        df_temp = resolver.enrich_dataframe(
            df.copy(), "Customer_Postal_Code",
            add_gov=False, add_deleg=False, add_region=True,
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
            df.copy(), "Merchant_Postal_Code",
            add_gov=False, add_deleg=False, add_region=True,
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

    # Behavioral defaults
    behavioral_defaults = {
        "client_AVG_TX_Amount": 0.0, "client_AVG_TX_PerDay": 0.0,
        "client_amount_deviation": 0.0, "client_abs_amount_deviation": 0.0,
        "client_amount_max_ratio": 0.0, "client_amount_z_score": 0.0,
        "client_is_new_max": 0.0, "client_is_new_min": 0.0,
        "client_total_transactions": 0.0, "client_time_since_last_tx": 0.0,
        "client_hours_since_last_tx": 0.0, "client_total_days_since_first": 0.0,
        "client_usual_region": "Unknown", "client_is_unusual_region": 0.0,
        "client_region_freq": 0.0, "client_usual_channel": "Unknown",
        "client_is_unusual_channel": 0.0, "client_channel_freq": 0.0,
        "client_usual_category": "Unknown", "client_is_unusual_category": 0.0,
        "client_category_freq": 0.0, "client_gap_z_score": 0.0,
        "client_risk_score": 0.0,
    }
    for col, default_value in behavioral_defaults.items():
        if col not in df.columns:
            df[col] = default_value

    numeric_cols = df.select_dtypes(include=[np.number]).columns
    df[numeric_cols] = df[numeric_cols].astype(np.float32)

    return df, tx_map, behavior_map


def main():
    parser = argparse.ArgumentParser(description="Fraud inference with explainability")
    parser.add_argument("--model-path", required=True)
    parser.add_argument("--pipeline-path", required=True)
    parser.add_argument("--input", required=True)
    parser.add_argument("--threshold", type=float, default=0.5)
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
            explainability_config={"threshold": args.threshold},
        )

        if is_single:
            tx = tx_map[0]
            behavior = behavior_map[0]

            result = engine.predict_with_explanation(
                df_raw=df,
                transaction_data=tx,
                behavior_data=behavior,
            )

            # Normalize risk levels to English for Java
            result["risk_level"] = _normalize_risk_level(result.get("risk_level", "MEDIUM"))
            if "fraud_reason" in result and isinstance(result["fraud_reason"], dict):
                result["fraud_reason"]["risk_level"] = _normalize_risk_level(
                    result["fraud_reason"].get("risk_level", "MEDIUM")
                )

            # Enrich with behavioral signals
            resolver = get_resolver()
            postal_code = tx.get("postal_code_client", tx.get("Customer_Postal_Code", ""))
            client_region = resolver.get_region(postal_code) if postal_code else "Unknown"
            existing_region = behavior.get("client_usual_region", "Unknown")
            usual_region = existing_region if existing_region and existing_region != "Unknown" else client_region

            result["behavioral_signals"] = {
                "current_amount": float(tx.get("TX_Amount", tx.get("amount", 0)) or 0),
                "current_avg": float(behavior.get("client_AVG_TX_Amount", 0)),
                "current_total_tx": int(behavior.get("client_total_transactions", 0)),
                "is_first_transaction": int(behavior.get("client_total_transactions", 0)) == 0,
                "client_usual_region": usual_region,
                "client_is_unusual_region": (client_region != usual_region and client_region != "Unknown" and usual_region != "Unknown"),
                "client_region_freq": round(float(behavior.get("client_region_freq", 0.0)), 4),
                "client_usual_channel": behavior.get("client_usual_channel", tx.get("Channel", "Unknown")),
                "client_is_unusual_channel": False,
                "client_channel_freq": round(float(behavior.get("client_channel_freq", 0.0)), 4),
                "client_usual_category": behavior.get("client_usual_category", tx.get("category", "Unknown")),
                "client_is_unusual_category": False,
                "client_category_freq": round(float(behavior.get("client_category_freq", 0.0)), 4),
            }

            print(json.dumps(result, default=str, ensure_ascii=False))

        else:
            results = engine.predict_batch_with_explanation(
                df_raw=df,
                batch_items=batch,
            )

            # Enrich with behavioral signals
            resolver = get_resolver()
            for i, result in enumerate(results):
                tx = tx_map[i]
                behavior = behavior_map[i]
                postal_code = tx.get("postal_code_client", tx.get("Customer_Postal_Code", ""))
                client_region = resolver.get_region(postal_code) if postal_code else "Unknown"
                existing_region = behavior.get("client_usual_region", "Unknown")
                usual_region = existing_region if existing_region and existing_region != "Unknown" else client_region

                # Normalize risk levels to English for Java
                result["risk_level"] = _normalize_risk_level(result.get("risk_level", "MEDIUM"))
                if "fraud_reason" in result and isinstance(result["fraud_reason"], dict):
                    result["fraud_reason"]["risk_level"] = _normalize_risk_level(
                        result["fraud_reason"].get("risk_level", "MEDIUM")
                    )

                result["behavioral_signals"] = {
                    "current_amount": float(tx.get("TX_Amount", tx.get("amount", 0)) or 0),
                    "current_avg": float(behavior.get("client_AVG_TX_Amount", 0)),
                    "current_total_tx": int(behavior.get("client_total_transactions", 0)),
                    "is_first_transaction": int(behavior.get("client_total_transactions", 0)) == 0,
                    "client_usual_region": usual_region,
                    "client_is_unusual_region": (client_region != usual_region and client_region != "Unknown" and usual_region != "Unknown"),
                    "client_region_freq": round(float(behavior.get("client_region_freq", 0.0)), 4),
                    "client_usual_channel": behavior.get("client_usual_channel", tx.get("Channel", "Unknown")),
                    "client_is_unusual_channel": False,
                    "client_channel_freq": round(float(behavior.get("client_channel_freq", 0.0)), 4),
                    "client_usual_category": behavior.get("client_usual_category", tx.get("category", "Unknown")),
                    "client_is_unusual_category": False,
                    "client_category_freq": round(float(behavior.get("client_category_freq", 0.0)), 4),
                }

            print(json.dumps({"results": results}, default=str, ensure_ascii=False))

    except Exception as e:
        print(f"ERROR: {str(e)}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == "__main__":
    main()