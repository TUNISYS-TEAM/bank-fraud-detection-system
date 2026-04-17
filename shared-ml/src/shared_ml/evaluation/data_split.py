from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

import pandas as pd


def temporal_split(
    df: pd.DataFrame,
    train_ratio: float = 0.70,
    val_ratio: float = 0.15,
    time_col: str = "TX_Time",
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """
    Split a dataframe into TRAIN / VAL / TEST using chronological order.
    This must be done before fitting transformers to avoid data leakage.
    """
    if df.empty:
        raise ValueError("Input dataframe is empty.")

    if time_col not in df.columns:
        raise ValueError(f"Column '{time_col}' not found in dataframe.")

    if not (0 < train_ratio < 1):
        raise ValueError("train_ratio must be in the range (0, 1).")

    if not (0 <= val_ratio < 1):
        raise ValueError("val_ratio must be in the range [0, 1).")

    if train_ratio + val_ratio >= 1:
        raise ValueError("train_ratio + val_ratio must be strictly less than 1.")

    df = df.copy()
    df[time_col] = pd.to_datetime(df[time_col])
    df = df.sort_values(time_col).reset_index(drop=True)

    n = len(df)
    i_train = int(n * train_ratio)
    i_val = int(n * (train_ratio + val_ratio))

    df_train = df.iloc[:i_train].copy()
    df_val = df.iloc[i_train:i_val].copy()
    df_test = df.iloc[i_val:].copy()

    _print_split_summary(df_train, df_val, df_test, time_col)
    return df_train, df_val, df_test


def save_split_report(
    df_train: pd.DataFrame,
    df_val: pd.DataFrame,
    df_test: pd.DataFrame,
    output_path: str | Path = "models/split_report.json",
    time_col: str = "TX_Time",
    target_col: str = "fraud",
):
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    report = {
        "timestamp": datetime.now().isoformat(),
        "splits": {},
    }

    for name, df in [("train", df_train), ("val", df_val), ("test", df_test)]:
        report["splits"][name] = {
            "n_rows": len(df),
            "period": f"{df[time_col].min()} → {df[time_col].max()}",
            "fraud_rate": float(df[target_col].mean()) if target_col in df.columns else None,
            "n_clients": int(df["Account_Id"].nunique()) if "Account_Id" in df.columns else None,
        }

    output_path.write_text(
        json.dumps(report, indent=2, default=str),
        encoding="utf-8",
    )
    print(f"  Split report saved → {output_path}")


def _print_split_summary(
    df_train: pd.DataFrame,
    df_val: pd.DataFrame,
    df_test: pd.DataFrame,
    time_col: str,
):
    print("\n  Temporal split summary:")
    for name, df in [("TRAIN", df_train), ("VAL", df_val), ("TEST", df_test)]:
        fraud_info = ""
        if "fraud" in df.columns:
            fraud_info = f"  fraud={df['fraud'].mean() * 100:.2f}%"

        print(
            f"    {name:<6}: {len(df):>8,} rows  "
            f"{df[time_col].min()} → {df[time_col].max()}"
            f"{fraud_info}"
        )