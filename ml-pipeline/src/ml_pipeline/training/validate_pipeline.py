from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score


def check_auc_leakage(
    df_train: pd.DataFrame,
    df_val: pd.DataFrame,
    df_test: pd.DataFrame,
    target_col: str = "fraud",
    features: list[str] | None = None,
):
    """
    Affiche l'AUC pour les caractéristiques sélectionnées à travers les différents jeux de données (splits).
    Signale les fuites de données potentielles lorsque l'AUC de test est beaucoup plus élevée que celle d'entraînement.
    """
    if features is None:
        features = [
            "client_amount_deviation",
            "client_abs_amount_deviation",
            "client_amount_max_ratio",
            "client_risk_score",
            "client_hours_since_last_tx",
            "client_is_unusual_region",
            "client_is_unusual_channel",
            "client_is_unusual_category",
            "client_is_new_max",
        ]

    print(f"\n{'─' * 100}")
    print(f"  {'Feature':<35} {'TRAIN':>10} {'VAL':>10} {'TEST':>10}  STATUS")
    print(f"{'─' * 100}")

    for feat in features:
        aucs = {}
        for name, df in [("TRAIN", df_train), ("VAL", df_val), ("TEST", df_test)]:
            if feat in df.columns and target_col in df.columns and df[target_col].nunique() == 2:
                try:
                    aucs[name] = roc_auc_score(df[target_col], df[feat])
                except Exception:
                    aucs[name] = float("nan")
            else:
                aucs[name] = float("nan")

        train_auc = aucs.get("TRAIN", float("nan"))
        val_auc = aucs.get("VAL", float("nan"))
        test_auc = aucs.get("TEST", float("nan"))

        if np.isnan(train_auc) or np.isnan(test_auc):
            status = "N/A"
        elif test_auc > train_auc + 0.1:
            status = "LEAKAGE"
        elif test_auc > train_auc + 0.05:
            status = "SUSPECT"
        else:
            status = "OK"

        print(
            f"  {feat:<35} {train_auc:>10.4f} {val_auc:>10.4f} {test_auc:>10.4f}  {status}"
        )

    print(f"{'─' * 100}")


def check_as_of_correctness(
    df_behavior: pd.DataFrame,
    split_name: str,
    id_col: str = "Account_Id",
    time_col: str = "TX_Time",
    count_col: str = "client_total_transactions",
):
    """
    Vérifie que la première transaction de chaque client a un historique nul.
    """
    for col in [id_col, time_col, count_col]:
        if col not in df_behavior.columns:
            print(f"  ⚠️  {split_name}: '{col}' not found — skipping AS-OF check")
            return

    df_sorted = df_behavior.sort_values([id_col, time_col])
    first_txs = df_sorted.groupby(id_col).first().reset_index()
    zeros_pct = (first_txs[count_col] == 0).mean() * 100

    icon = "✅" if zeros_pct >= 99 else "❌"
    print(
        f"  {icon}  {split_name}: {zeros_pct:.1f}% of first transactions "
        f"have {count_col} = 0  (expect ≥99%)"
    )

    if zeros_pct < 99:
        bad = first_txs[first_txs[count_col] > 0]
        print(f"       {len(bad)} clients have non-zero history on first tx:")
        for _, row in bad.head(5).iterrows():
            print(f"       • {row[id_col]}: {count_col}={row[count_col]}")


def quick_signal_test(
    X_train: pd.DataFrame,
    y_train: pd.Series,
    X_test: pd.DataFrame,
    y_test: pd.Series,
    n_estimators: int = 50,
    max_depth: int = 5,
):
    """
    Ajuste une forêt aléatoire (Random Forest) superficielle pour évaluer rapidement si le jeu de caractéristiques contient un signal.
    Retourne (auc_train, auc_test).
    """
    from sklearn.ensemble import RandomForestClassifier

    if not isinstance(X_train, pd.DataFrame) or not isinstance(X_test, pd.DataFrame):
        raise TypeError("quick_signal_test expects pandas DataFrame inputs.")

    numeric_cols = X_train.select_dtypes(include=[np.number]).columns.tolist()
    if not numeric_cols:
        raise ValueError("No numeric columns found for quick_signal_test.")

    X_tr = X_train[numeric_cols].replace([np.inf, -np.inf], np.nan).fillna(0)
    X_te = X_test[numeric_cols].replace([np.inf, -np.inf], np.nan).fillna(0)

    rf = RandomForestClassifier(
        n_estimators=n_estimators,
        max_depth=max_depth,
        class_weight="balanced",
        random_state=42,
        n_jobs=-1,
    )
    rf.fit(X_tr, y_train)

    auc_train = roc_auc_score(y_train, rf.predict_proba(X_tr)[:, 1])
    auc_test = roc_auc_score(y_test, rf.predict_proba(X_te)[:, 1])

    icon = "✅" if auc_test > 0.70 else "⚠️" if auc_test > 0.65 else "❌"
    print(f"\n  {icon}  Quick RF signal test")
    print(f"       AUC TRAIN : {auc_train:.4f}")
    print(f"       AUC TEST  : {auc_test:.4f}")

    return auc_train, auc_test