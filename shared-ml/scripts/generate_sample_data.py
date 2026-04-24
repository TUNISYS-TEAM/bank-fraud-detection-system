"""
Génère des données de transactions synthétiques avec comportement client réaliste.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

from shared_ml.preprocessing.behavioral.client_behavior import ClientBehaviorTransformer

# ---------------------------------------------------------------------------
# Load valid Tunisian postal codes from the resolver JSON
# ---------------------------------------------------------------------------
_ZIP_GOV_MAP = (
    Path(__file__).resolve().parents[1]
    / "src"
    / "shared_ml"
    / "utils"
    / "resources"
    / "zip_gov_map.json"
)


def _load_valid_postal_codes() -> list[str]:
    try:
        with open(_ZIP_GOV_MAP, encoding="utf-8") as f:
            entries = json.load(f)
        codes = [str(e["zip"]).strip().zfill(4) for e in entries]
        if codes:
            return codes
    except Exception as exc:
        print(f"WARNING: Could not load postal codes from {_ZIP_GOV_MAP}: {exc}", file=sys.stderr)
    return ["1000", "2000", "3000", "4000", "5000", "6000", "7000", "8000", "9000"]


VALID_POSTAL_CODES: list[str] = _load_valid_postal_codes()

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "data" / "samples"

REGIONS = ["Grand_Tunis", "Nord_Est", "Nord_Ouest", "Centre_Est", "Centre_Ouest", "Sud_Est", "Sud_Ouest"]
CHANNELS = ["GAB", "Mobile", "En_Ligne", "Virement", "TPE"]

CATEGORIES = [
    "'es_barsandrestaurants'",
    "'es_contents'",
    "'es_fashion'",
    "'es_food'",
    "'es_health'",
    "'es_home'",
    "'es_hotelservices'",
    "'es_hyper'",
    "'es_leisure'",
    "'es_otherservices'",
    "'es_sportsandtoys'",
    "'es_tech'",
    "'es_transportation'",
    "'es_travel'",
    "'es_wellnessandbeauty'",
]

IP_POOLS = {
    "Tunisie": [
        f"197.{a}.{b}.{c}"
        for a in range(0, 8)
        for b in range(0, 256, 16)
        for c in range(1, 255, 32)
    ],
    "France": [
        f"90.{a}.{b}.{c}"
        for a in range(0, 128, 16)
        for b in range(0, 256, 32)
        for c in range(1, 254, 48)
    ],
    "International": [
        f"{a}.{b}.{c}.{d}"
        for a in [45, 156, 185]
        for b in range(0, 256, 64)
        for c in range(0, 256, 64)
        for d in range(1, 254, 64)
    ],
}


def sample_object_list(items: list[dict], size: int, rng: np.random.Generator) -> list[dict]:
    if size <= 0 or not items:
        return []
    if size >= len(items):
        return list(items)
    indices = rng.choice(len(items), size=size, replace=False)
    return [items[int(i)] for i in indices]


def generate_client_profiles(n_clients: int, seed: int) -> list[dict]:
    rng = np.random.default_rng(seed)
    profiles = []
    for i in range(n_clients):
        base_amount = rng.lognormal(mean=4.0, sigma=0.8)
        tx_per_day = rng.uniform(0.05, 4.0)

        usual_region = rng.choice(REGIONS)
        usual_channel = rng.choice(CHANNELS)
        usual_category = rng.choice(CATEGORIES)

        profiles.append({
            "client_id": f"C{i+1:06d}",
            "account_id": f"A{i+1:08d}",
            "base_amount": base_amount,
            "amount_std": base_amount * 0.3,
            "tx_per_day": tx_per_day,
            "usual_region": usual_region,
            "usual_channel": usual_channel,
            "usual_category": usual_category,
            "device_id": f"D{1000 + (i % 9000):04d}",
        })
    return profiles


def apply_drift(client: dict, progress: float, drift_strength: float) -> dict:
    drift_factor = 1.0 + (drift_strength - 1.0) * progress
    drifted = client.copy()
    drifted["base_amount"] = client["base_amount"] * drift_factor
    drifted["tx_per_day"] = client["tx_per_day"] * drift_factor
    return drifted


def generate_ip(client: dict, is_fraud: bool, rng: np.random.Generator) -> str:
    if is_fraud:
        if rng.random() < 0.2:
            return rng.choice(IP_POOLS["Tunisie"])
        return rng.choice(IP_POOLS["International"])

    if rng.random() < 0.1:
        return rng.choice(IP_POOLS["International"])
    return rng.choice(IP_POOLS["Tunisie"])


def generate_normal_transaction(
    client: dict, timestamp: datetime, rng: np.random.Generator, is_fraud: bool = False
) -> dict:
    amount = max(1.0, rng.normal(client["base_amount"], client["amount_std"]))

    region = client["usual_region"] if rng.random() < 0.8 else rng.choice(REGIONS)
    channel = client["usual_channel"] if rng.random() < 0.8 else rng.choice(CHANNELS)
    category = client["usual_category"] if rng.random() < 0.8 else rng.choice(CATEGORIES)

    return {
        "TX_Time": timestamp,
        "TX_Amount": round(float(amount), 2),
        "Channel": channel,
        "category": category,
        "region": region,
        "client_id": client["client_id"],
        "account_id": client["account_id"],
        "device_id": client["device_id"],
        "ip_address": generate_ip(client, is_fraud, rng),
        "Customer_Postal_Code": rng.choice(VALID_POSTAL_CODES),
        "Merchant_Postal_Code": rng.choice(VALID_POSTAL_CODES),
        "currency": "TND",
    }


def generate_fraud_transaction(
    client: dict, timestamp: datetime, fraud_type: str, rng: np.random.Generator, subtle: bool = False
) -> tuple[dict, str]:
    tx = generate_normal_transaction(client, timestamp, rng, is_fraud=True)
    applied_type = fraud_type

    if fraud_type == "amount_spike":
        mult = rng.uniform(1.2, 2.2) if subtle else rng.uniform(1.5, 3.0)
        tx["TX_Amount"] = round(tx["TX_Amount"] * mult, 2)

    elif fraud_type == "geo_anomaly":
        other_regions = [r for r in REGIONS if r != client["usual_region"]]
        tx["region"] = rng.choice(other_regions) if other_regions else REGIONS[0]
        tx["Channel"] = "En_Ligne"

    elif fraud_type == "channel_anomaly":
        other_channels = [c for c in CHANNELS if c != client["usual_channel"]]
        tx["Channel"] = rng.choice(other_channels) if other_channels else "GAB"

    elif fraud_type == "high_amount":
        tx["TX_Amount"] = round(float(rng.lognormal(mean=4.8, sigma=0.35)), 2)

    elif fraud_type == "subtle_timing":
        dt = tx["TX_Time"]
        tx["TX_Time"] = dt.replace(hour=int(rng.integers(0, 6)), minute=int(rng.integers(0, 59)))

    elif fraud_type == "mixed":
        if rng.random() < 0.5:
            mult = rng.uniform(1.2, 2.2) if subtle else rng.uniform(1.5, 3.0)
            tx["TX_Amount"] = round(tx["TX_Amount"] * mult, 2)
            applied_type = "amount_spike"
        if rng.random() < 0.3:
            other_regions = [r for r in REGIONS if r != client["usual_region"]]
            tx["region"] = rng.choice(other_regions) if other_regions else REGIONS[0]
            tx["Channel"] = "En_Ligne"
            applied_type = "geo_anomaly" if applied_type == "mixed" else "mixed"

    else:
        mult = rng.uniform(1.2, 2.2) if subtle else rng.uniform(1.5, 3.0)
        tx["TX_Amount"] = round(tx["TX_Amount"] * mult, 2)
        applied_type = "amount_spike"

    return tx, applied_type


def generate_transactions(
    profiles: list[dict],
    start_time: datetime,
    end_time: datetime,
    total_transactions: int,
    exact_fraud_count: int,
    scenario: str,
    time_distribution: str,
    drift_strength: float,
    min_tx_per_client: int,
    subtle_fraud: bool,
    rng: np.random.Generator,
) -> list[dict]:
    all_txs: list[dict] = []
    total_duration_seconds = int((end_time - start_time).total_seconds())
    n_clients = len(profiles)

    pareto_shape = 1.5
    weights = rng.pareto(pareto_shape, size=n_clients)
    weights = weights / weights.sum()

    client_tx_counts = []
    for i, client in enumerate(profiles):
        base_count = client["tx_per_day"] * (total_duration_seconds / 86400)
        count = max(min_tx_per_client, int(base_count * weights[i] * n_clients))
        client_tx_counts.append(count)

    total_estimated = sum(client_tx_counts)
    if total_estimated > 0:
        scaling = total_transactions / total_estimated
        client_tx_counts = [max(min_tx_per_client, int(c * scaling)) for c in client_tx_counts]

    remaining_frauds = exact_fraud_count
    fraud_indices = set()
    all_indices = list(range(sum(client_tx_counts)))
    shuffled_indices = rng.permutation(all_indices)

    for idx in shuffled_indices:
        if remaining_frauds <= 0:
            break
        fraud_indices.add(int(idx))
        remaining_frauds -= 1

    tx_idx = 0
    frauds_generated = 0

    for client, tx_count in zip(profiles, client_tx_counts):
        if tx_count <= 0:
            continue

        if time_distribution == "uniform":
            timestamps = [
                start_time + timedelta(seconds=int(rng.integers(0, total_duration_seconds)))
                for _ in range(tx_count)
            ]
        elif time_distribution == "night":
            timestamps = []
            for _ in range(tx_count):
                dt = start_time + timedelta(seconds=int(rng.integers(0, total_duration_seconds)))
                dt = dt.replace(hour=int(rng.integers(0, 6)), minute=int(rng.integers(0, 59)))
                timestamps.append(dt)
        elif time_distribution == "burst":
            timestamps = []
            n_bursts = max(1, tx_count // 50)
            burst_centers = [
                start_time + timedelta(seconds=int(rng.integers(0, total_duration_seconds)))
                for _ in range(n_bursts)
            ]
            for _ in range(tx_count):
                if rng.random() < 0.7:
                    center = rng.choice(burst_centers)
                    offset = int(rng.integers(-300, 300))
                    timestamps.append(center + timedelta(seconds=offset))
                else:
                    timestamps.append(start_time + timedelta(seconds=int(rng.integers(0, total_duration_seconds))))
        else:
            timestamps = []
            weekday_hour_weights = np.array([
                0.01, 0.005, 0.005, 0.005, 0.01, 0.02,
                0.04, 0.06, 0.08, 0.07, 0.06, 0.05,
                0.06, 0.07, 0.06, 0.05, 0.06, 0.08,
                0.09, 0.07, 0.04, 0.03, 0.02, 0.01
            ])
            weekend_hour_weights = np.array([
                0.005, 0.002, 0.002, 0.002, 0.005, 0.01,
                0.02, 0.03, 0.04, 0.05, 0.06, 0.07,
                0.08, 0.07, 0.06, 0.06, 0.07, 0.08,
                0.09, 0.08, 0.06, 0.04, 0.02, 0.01
            ])
            weekday_hour_weights /= weekday_hour_weights.sum()
            weekend_hour_weights /= weekend_hour_weights.sum()

            for _ in range(tx_count):
                dt = start_time + timedelta(seconds=int(rng.integers(0, total_duration_seconds)))
                if dt.weekday() < 5:
                    h = int(rng.choice(24, p=weekday_hour_weights))
                else:
                    h = int(rng.choice(24, p=weekend_hour_weights))
                dt = dt.replace(hour=h, minute=int(rng.integers(0, 59)))
                timestamps.append(dt)

        timestamps.sort()

        fraud_timestamps = []
        normal_timestamps = []
        for ts in timestamps:
            if tx_idx in fraud_indices:
                fraud_timestamps.append(ts)
            else:
                normal_timestamps.append(ts)
            tx_idx += 1

        def get_drifted_client(ts: datetime) -> dict:
            if drift_strength > 1.0:
                progress = (ts - start_time).total_seconds() / total_duration_seconds
                return apply_drift(client, progress, drift_strength)
            return client

        for ts in normal_timestamps:
            drifted = get_drifted_client(ts)
            tx = generate_normal_transaction(drifted, ts, rng, is_fraud=False)
            tx["fraud"] = 0
            tx["fraud_type"] = "none"
            all_txs.append(tx)

        for ts in fraud_timestamps:
            drifted = get_drifted_client(ts)

            if scenario == "mixed":
                fraud_type = rng.choice([
                    "amount_spike", "geo_anomaly", "channel_anomaly",
                    "high_amount", "subtle_timing", "mixed"
                ])
            elif scenario == "high_amount":
                fraud_type = "high_amount"
            elif scenario == "geo_anomaly":
                fraud_type = "geo_anomaly"
            elif scenario == "high_frequency":
                fraud_type = "frequency"
            elif scenario == "sophisticated":
                fraud_type = rng.choice([
                    "amount_spike", "geo_anomaly", "channel_anomaly", "subtle_timing"
                ])
            else:
                fraud_type = "amount_spike"

            if fraud_type == "frequency":
                remaining = max(0, exact_fraud_count - frauds_generated)
                if remaining <= 0:
                    continue

                max_cluster_size = min(6, remaining)
                cluster_size = int(rng.integers(2, max_cluster_size + 1))
                base_ts = ts

                for _ in range(cluster_size):
                    tx2, ftype = generate_fraud_transaction(
                        drifted, base_ts, "amount_spike", rng, subtle_fraud
                    )
                    tx2["fraud"] = 1
                    tx2["fraud_type"] = f"frequency_{ftype}"
                    all_txs.append(tx2)
                    frauds_generated += 1
                    base_ts += timedelta(minutes=int(rng.integers(1, 10)))
            else:
                tx, applied_type = generate_fraud_transaction(
                    drifted, ts, fraud_type, rng, subtle_fraud
                )
                tx["fraud"] = 1
                tx["fraud_type"] = applied_type
                all_txs.append(tx)
                frauds_generated += 1

    if len(all_txs) > total_transactions:
        fraud_txs = [tx for tx in all_txs if tx["fraud"] == 1]
        normal_txs = [tx for tx in all_txs if tx["fraud"] == 0]

        if len(fraud_txs) > total_transactions:
            fraud_txs = sample_object_list(fraud_txs, total_transactions, rng)
            normal_txs = []
        else:
            needed_normals = total_transactions - len(fraud_txs)
            if needed_normals < len(normal_txs):
                normal_txs = sample_object_list(normal_txs, needed_normals, rng)

        all_txs = fraud_txs + normal_txs
        random.Random(int(rng.integers(0, 1_000_000))).shuffle(all_txs)

    elif len(all_txs) < total_transactions:
        while len(all_txs) < total_transactions:
            client = profiles[int(rng.integers(0, len(profiles)))]
            ts = start_time + timedelta(seconds=int(rng.integers(0, total_duration_seconds)))
            tx = generate_normal_transaction(client, ts, rng, is_fraud=False)
            tx["fraud"] = 0
            tx["fraud_type"] = "none"
            all_txs.append(tx)

    all_txs.sort(key=lambda x: x["TX_Time"])
    return all_txs


def enrich_with_client_behavior(df: pd.DataFrame) -> pd.DataFrame:
    df_behavior = df.rename(columns={"account_id": "Account_Id"}).copy()
    df_behavior["TX_Time"] = pd.to_datetime(df_behavior["TX_Time"], errors="coerce")

    required_cols = {"Account_Id", "TX_Time", "TX_Amount", "Customer_Postal_Code", "Channel", "category"}
    missing = required_cols - set(df_behavior.columns)
    if missing:
        raise ValueError(f"Missing required columns for ClientBehaviorTransformer: {sorted(missing)}")

    transformer = ClientBehaviorTransformer(
        smoothing_days=1,
        default_time_gap_days=30,
        strict_as_of=True,
    )

    behavior_features = transformer.transform(df_behavior, use_fitted_history=False)

    final_df = pd.concat(
        [
            df_behavior[
                [
                    "transaction_id",
                    "fraud",
                    "fraud_type",
                    "Account_Id",
                    "TX_Time",
                    "TX_Amount",
                    "Channel",
                    "category",
                    "Customer_Postal_Code",
                    "Merchant_Postal_Code",
                    "region",
                    "client_id",
                    "device_id",
                    "currency",
                    "ip_address",
                ]
            ].reset_index(drop=True),
            behavior_features.drop(columns=["TX_Time", "TX_Amount"], errors="ignore").reset_index(drop=True),
        ],
        axis=1,
    )

    return final_df


def generate_sample_data(
    output_dir: str | Path = DEFAULT_OUTPUT_DIR,
    n_transactions: int = 10_000,
    fraud_ratio: float = 0.02,
    fraud_count: int | None = None,
    n_clients: int = 1_000,
    start_date: str | None = "2025-01-01",
    end_date: str | None = "2025-12-31",
    period_hours: int | None = None,
    scenario: str = "mixed",
    time_distribution: str = "realistic",
    drift_strength: float = 1.0,
    min_tx_per_client: int = 15,
    subtle_fraud: bool = False,
    output_format: str = "parquet",
    seed: int = 42,
) -> None:
    if fraud_count is not None and fraud_count > 0:
        exact_fraud = fraud_count
    else:
        exact_fraud = int(round(n_transactions * fraud_ratio))

    if n_transactions <= 0:
        raise ValueError("n_transactions must be > 0.")
    if not (0 <= exact_fraud <= n_transactions):
        raise ValueError("Fraud count must be between 0 and total transactions.")

    rng = np.random.default_rng(seed)
    random.seed(seed)

    if period_hours:
        end = datetime.now()
        start = end - timedelta(hours=period_hours)
    else:
        start = datetime.fromisoformat(start_date or "2025-01-01")
        end = datetime.fromisoformat(end_date or "2025-12-31")

    if end <= start:
        raise ValueError("end_date must be after start_date.")

    profiles = generate_client_profiles(n_clients, seed)

    transactions = generate_transactions(
        profiles,
        start,
        end,
        n_transactions,
        exact_fraud,
        scenario,
        time_distribution,
        drift_strength,
        min_tx_per_client,
        subtle_fraud,
        rng,
    )

    for i, tx in enumerate(transactions):
        tx["transaction_id"] = f"T{i:010d}"

    df = pd.DataFrame(transactions)
    df["TX_Time"] = pd.to_datetime(df["TX_Time"])

    df_final = enrich_with_client_behavior(df)

    if output_format == "json":
        json_df = df_final.copy()
        json_df["TX_Time"] = json_df["TX_Time"].astype(str)
        print(json.dumps(json_df.to_dict(orient="records"), ensure_ascii=False))
    else:
        out_path = Path(output_dir)
        out_path.mkdir(parents=True, exist_ok=True)
        output_file = out_path / "transactions.parquet"
        df_final.to_parquet(output_file, index=False)
        print(f"Generated enriched dataset -> {output_file}", file=sys.stderr)
        print(f"Fraud count: {df_final['fraud'].sum()} / {len(df_final)} ({df_final['fraud'].mean():.2%})", file=sys.stderr)
        print(f"Final shape: {df_final.shape}", file=sys.stderr)
        print(f"Final columns: {list(df_final.columns)}", file=sys.stderr)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Générateur de transactions synthétiques avancé.")
    parser.add_argument("--output", "-o", default=str(DEFAULT_OUTPUT_DIR))
    parser.add_argument("--n-transactions", "-n", type=int, default=10_000)
    parser.add_argument("--fraud-ratio", "-f", type=float, default=0.02)
    parser.add_argument("--fraud-count", type=int, help="Nombre exact de fraudes (prioritaire)")
    parser.add_argument("--n-clients", "-c", type=int, default=1_000)
    parser.add_argument("--start-date", default="2025-01-01")
    parser.add_argument("--end-date", default="2025-12-31")
    parser.add_argument("--period-hours", type=int)
    parser.add_argument(
        "--scenario",
        default="mixed",
        choices=["mixed", "high_amount", "geo_anomaly", "high_frequency", "sophisticated"]
    )
    parser.add_argument(
        "--time-distribution",
        default="realistic",
        choices=["uniform", "realistic", "night", "burst"]
    )
    parser.add_argument("--drift-strength", type=float, default=1.0)
    parser.add_argument("--min-tx-per-client", type=int, default=15)
    parser.add_argument("--subtle-fraud", action="store_true")
    parser.add_argument("--output-format", default="parquet", choices=["parquet", "json"])
    parser.add_argument("--seed", type=int, default=42)

    args = parser.parse_args()
    generate_sample_data(
        output_dir=args.output,
        n_transactions=args.n_transactions,
        fraud_ratio=args.fraud_ratio,
        fraud_count=args.fraud_count,
        n_clients=args.n_clients,
        start_date=args.start_date,
        end_date=args.end_date,
        period_hours=args.period_hours,
        scenario=args.scenario,
        time_distribution=args.time_distribution,
        drift_strength=args.drift_strength,
        min_tx_per_client=args.min_tx_per_client,
        subtle_fraud=args.subtle_fraud,
        output_format=args.output_format,
        seed=args.seed,
    )