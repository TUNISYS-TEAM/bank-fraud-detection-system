"""
Génère des données de transactions synthétiques pour tester le pipeline ML.

Sortie :
    - transactions.parquet : échantillon de transactions brutes avec étiquettes de fraude
"""

from __future__ import annotations

import argparse
import random
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "data" / "samples"


def generate_sample_data(
    output_dir: str | Path = DEFAULT_OUTPUT_DIR,
    n_transactions: int = 10_000,
    fraud_ratio: float = 0.02,
    n_clients: int = 1_000,
    start_date: str = "2025-01-01",
    end_date: str = "2025-12-31",
    seed: int = 42,
) -> None:
    """
    Génère des données de transactions synthétiques et les sauvegarde dans un fichier parquet.

    Paramètres
    ----------
    output_dir : str | Path
        Répertoire où le fichier parquet sera sauvegardé.
    n_transactions : int
        Nombre de transactions à générer.
    fraud_ratio : float
        Fraction cible de transactions frauduleuses, entre 0 et 1.
    n_clients : int
        Nombre de clients uniques.
    start_date : str
        Date de début pour les transactions générées (YYYY-MM-DD).
    end_date : str
        Date de fin pour les transactions générées (YYYY-MM-DD).
    seed : int
        Graine aléatoire pour la reproductibilité.
    """
    if not (0 <= fraud_ratio <= 1):
        raise ValueError("fraud_ratio must be between 0 and 1.")

    if n_transactions <= 0:
        raise ValueError("n_transactions must be > 0.")

    if n_clients <= 0:
        raise ValueError("n_clients must be > 0.")

    np.random.seed(seed)
    random.seed(seed)

    out_path = Path(output_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    start = datetime.fromisoformat(start_date)
    end = datetime.fromisoformat(end_date)

    if end <= start:
        raise ValueError("end_date must be after start_date.")

    delta_seconds = int((end - start).total_seconds())

    client_ids = [f"C{str(i).zfill(6)}" for i in range(1, n_clients + 1)]
    account_ids = [f"A{str(i).zfill(8)}" for i in range(1, n_clients + 1)]

    regions = [
        "Grand_Tunis",
        "Nord_Est",
        "Nord_Ouest",
        "Centre_Est",
        "Centre_Ouest",
        "Sud_Est",
        "Sud_Ouest",
    ]
    channels = ["ATM", "Online", "Mobile", "POS"]
    categories = ["shopping", "food", "bills", "entertainment", "travel", "gambling"]

    timestamps = [
        start + timedelta(seconds=random.randint(0, delta_seconds))
        for _ in range(n_transactions)
    ]
    timestamps.sort()

    client_risk = {cid: np.random.beta(2, 5) for cid in client_ids}
    client_to_account = dict(zip(client_ids, account_ids))

    rows: list[dict] = []
    for i, ts in enumerate(timestamps):
        client_id = random.choice(client_ids)
        account_id = client_to_account[client_id]

        amount = np.random.lognormal(mean=4.5, sigma=0.8)
        channel = random.choice(channels)
        category = random.choice(categories)
        region = random.choice(regions)

        rows.append(
            {
                "transaction_id": f"T{i:08d}",
                "client_id": client_id,
                "Account_Id": account_id,
                "TX_Time": ts,
                "TX_Amount": round(float(amount), 2),
                "Customer_Postal_Code": f"{np.random.randint(1000, 9999)}",
                "Channel": channel,
                "Category": category,
                "Region": region,
                "client_risk_score": float(client_risk[client_id]),
            }
        )

    df = pd.DataFrame(rows)

    n_frauds = int(round(n_transactions * fraud_ratio))
    fraud_flags = np.array([1] * n_frauds + [0] * (n_transactions - n_frauds))
    np.random.shuffle(fraud_flags)
    df["fraud"] = fraud_flags

    output_file = out_path / "transactions.parquet"
    df.to_parquet(output_file, index=False)

    print(f"Generated {len(df)} transactions -> {output_file}")
    print(f"Fraud rate: {df['fraud'].mean():.2%}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Génère des données de transactions synthétiques.")
    parser.add_argument(
        "--output",
        "-o",
        default=str(DEFAULT_OUTPUT_DIR),
        help="Répertoire de sortie",
    )
    parser.add_argument(
        "--n-transactions",
        "-n",
        type=int,
        default=10_000,
        help="Nombre de transactions",
    )
    parser.add_argument(
        "--fraud-ratio",
        "-f",
        type=float,
        default=0.02,
        help="Ratio de fraude cible entre 0 et 1",
    )
    parser.add_argument(
        "--n-clients",
        "-c",
        type=int,
        default=1_000,
        help="Nombre de clients",
    )
    parser.add_argument(
        "--start-date",
        default="2025-01-01",
        help="Date de début (YYYY-MM-DD)",
    )
    parser.add_argument(
        "--end-date",
        default="2025-12-31",
        help="Date de fin (YYYY-MM-DD)",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Graine aléatoire",
    )
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