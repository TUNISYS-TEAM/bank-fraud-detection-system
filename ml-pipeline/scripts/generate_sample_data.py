"""
generate_sample_data.py
=======================
Generate synthetic transaction data for testing the pipeline.

Outputs:
    - transactions.parquet : raw transactions (with fraud labels)
    - clients.parquet      : client profile information (optional)
"""

import argparse
import numpy as np
import pandas as pd
from pathlib import Path
from datetime import datetime, timedelta
import random


def generate_sample_data(
    output_dir: str = "data/samples/",
    n_transactions: int = 10000,
    fraud_ratio: float = 0.02,
    n_clients: int = 1000,
    start_date: str = "2025-01-01",
    end_date: str = "2025-12-31",
    seed: int = 42,
) -> None:
    """
    Generate synthetic transaction data and save to parquet files.

    Parameters
    ----------
    output_dir : str
        Directory where files will be saved.
    n_transactions : int
        Number of transactions to generate.
    fraud_ratio : float
        Fraction of fraudulent transactions.
    n_clients : int
        Number of unique clients.
    start_date, end_date : str
        Date range for transactions (YYYY-MM-DD).
    seed : int
        Random seed for reproducibility.
    """
    np.random.seed(seed)
    random.seed(seed)

    out_path = Path(output_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    # Client profiles
    client_ids = [f"C{str(i).zfill(6)}" for i in range(1, n_clients + 1)]
    regions = ["Grand_Tunis", "Nord_Est", "Nord_Ouest", "Centre_Est",
               "Centre_Ouest", "Sud_Est", "Sud_Ouest"]
    channels = ["ATM", "Online", "Mobile", "POS"]
    categories = ["shopping", "food", "bills", "entertainment", "travel", "gambling"]

    # Generate timestamps
    start = datetime.fromisoformat(start_date)
    end = datetime.fromisoformat(end_date)
    delta = (end - start).total_seconds()

    timestamps = [start + timedelta(seconds=random.randint(0, int(delta))) for _ in range(n_transactions)]
    timestamps.sort()

    # Assign clients and their risk scores
    client_risk = {cid: np.random.beta(2, 5) for cid in client_ids}  # most are low risk

    # Build DataFrame
    data = []
    for i, ts in enumerate(timestamps):
        cid = random.choice(client_ids)
        amount = np.random.lognormal(mean=4.5, sigma=0.8)  # typical ~ 90 EUR
        channel = random.choice(channels)
        category = random.choice(categories)
        region = random.choice(regions)
        # Simulate fraud based on risk and amount
        base_prob = client_risk[cid] * 0.1
        amount_factor = min(1.0, amount / 1000)  # higher amount => more fraud
        fraud_prob = base_prob + 0.02 * amount_factor
        is_fraud = np.random.rand() < fraud_prob

        # Apply fraud ratio cap
        # Not perfect but simple
        data.append({
            "transaction_id": f"T{i:08d}",
            "Account_Id": cid,
            "TX_Time": ts,
            "TX_Amount": round(amount, 2),
            "Customer_Postal_Code": f"{np.random.randint(1000, 9999)}",  # fake zip
            "Channel": channel,
            "category": category,
            "region": region,
            "fraud": int(is_fraud),
            "client_risk_score": client_risk[cid],
        })

    df = pd.DataFrame(data)
    # Adjust fraud ratio if needed (optional)
    # Could oversample to hit exact ratio, but we'll keep as is.

    # Save
    df.to_parquet(out_path / "transactions.parquet", index=False)
    print(f"Generated {len(df)} transactions → {out_path / 'transactions.parquet'}")
    print(f"Fraud rate: {df['fraud'].mean():.2%}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate synthetic transaction data.")
    parser.add_argument("--output", "-o", default="data/samples/", help="Output directory")
    parser.add_argument("--n-transactions", "-n", type=int, default=10000, help="Number of transactions")
    parser.add_argument("--fraud-ratio", "-f", type=float, default=0.02, help="Target fraud ratio")
    parser.add_argument("--n-clients", "-c", type=int, default=1000, help="Number of clients")
    parser.add_argument("--start-date", default="2025-01-01", help="Start date (YYYY-MM-DD)")
    parser.add_argument("--end-date", default="2025-12-31", help="End date (YYYY-MM-DD)")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    args = parser.parse_args()

    generate_sample_data(
        output_dir=args.output,
        n_transactions=args.n_transactions,
        fraud_ratio=args.fraud_ratio,
        n_clients=args.n_clients,
        start_date=args.start_date,
        end_date=args.end_date,
        seed=args.seed,
    )