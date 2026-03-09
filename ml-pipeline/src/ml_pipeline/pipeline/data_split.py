# src/ml_pipeline/pipeline/data_split.py
"""
Temporal train/val/test split — no shuffle, no stratify.
Must be applied BEFORE fitting any transformer to avoid leakage.
"""

from __future__ import annotations

import json
from datetime import datetime

import pandas as pd


def temporal_split(
    df: pd.DataFrame,
    train_ratio: float = 0.70,
    val_ratio: float = 0.15,
    time_col: str = "TX_Time",
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """
    Split df into TRAIN / VAL / TEST using chronological order.

    Parameters
    ----------
    df          : raw DataFrame (will be sorted in place copy)
    train_ratio : fraction of rows for training
    val_ratio   : fraction of rows for validation
    time_col    : name of the datetime column

    Returns
    -------
    df_train, df_val, df_test
    """
    df = df.copy().sort_values(time_col).reset_index(drop=True)
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
    output_path: str = "models/split_report.json",
    time_col: str = "TX_Time",
    target_col: str = "fraud",
):
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

    with open(output_path, "w") as f:
        json.dump(report, f, indent=2, default=str)
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
            fraud_info = f"  fraud={df['fraud'].mean()*100:.2f}%"
        print(
            f"    {name:<6}: {len(df):>8,} rows  "
            f"{df[time_col].min()} → {df[time_col].max()}"
            f"{fraud_info}"
        )