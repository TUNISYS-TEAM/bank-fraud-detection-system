"""
Convertit les données de transactions brutes en jeux de caractéristiques spécifiques aux pipelines.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd

from ml_pipeline.pipeline.data_split import temporal_split, save_split_report
from shared_ml.pipeline.fraud_pipeline import (
    FraudFeaturePipeline,
    create_catboost_pipeline,
    create_dl_pipeline,
    create_tree_pipeline,
)

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = PROJECT_ROOT / "data" / "samples" / "transactions.parquet"
DEFAULT_PARQUET_DIR = PROJECT_ROOT / "artifacts" / "exported_parquet"
DEFAULT_REPORTS_DIR = PROJECT_ROOT / "artifacts" / "reports"
DEFAULT_MODELS_DIR = PROJECT_ROOT / "artifacts" / "exported_models"

REQUIRED_RAW_COLS = {
    "Account_Id",
    "TX_Time",
    "TX_Amount",
    "Customer_Postal_Code",
    "Channel",
    "category",
    "fraud",
}


def _normalize_raw_columns(df: pd.DataFrame) -> pd.DataFrame:
    """
    Normalise les noms de colonnes du dataset brut pour les aligner
    avec le contrat attendu par FraudFeaturePipeline.
    """
    df = df.copy()
    df.columns = [str(c).strip() for c in df.columns]

    rename_map = {}

    if "Category" in df.columns and "category" not in df.columns:
        rename_map["Category"] = "category"

    if rename_map:
        df = df.rename(columns=rename_map)

    return df


def _validate_raw_input(df: pd.DataFrame) -> None:
    missing = REQUIRED_RAW_COLS - set(df.columns)
    if missing:
        raise ValueError(
            f"Colonnes requises manquantes dans le dataset brut : {sorted(missing)}"
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Prétraite les transactions brutes en jeux de caractéristiques spécifiques aux modèles."
    )
    parser.add_argument(
        "--input",
        default=str(DEFAULT_INPUT),
        help="Chemin vers le fichier parquet des transactions brutes",
    )
    parser.add_argument(
        "--output-parquet",
        default=str(DEFAULT_PARQUET_DIR),
        help="Répertoire de sortie pour les fichiers parquet traités",
    )
    parser.add_argument(
        "--output-reports",
        default=str(DEFAULT_REPORTS_DIR),
        help="Répertoire de sortie pour les rapports",
    )
    parser.add_argument(
        "--output-models",
        default=str(DEFAULT_MODELS_DIR),
        help="Répertoire de sortie pour les pipelines de prétraitement ajustés",
    )
    parser.add_argument(
        "--train-ratio",
        type=float,
        default=0.7,
        help="Fraction pour l'entraînement",
    )
    parser.add_argument(
        "--val-ratio",
        type=float,
        default=0.15,
        help="Fraction pour la validation",
    )
    args = parser.parse_args()

    parquet_dir = Path(args.output_parquet)
    reports_dir = Path(args.output_reports)
    models_dir = Path(args.output_models)

    parquet_dir.mkdir(parents=True, exist_ok=True)
    reports_dir.mkdir(parents=True, exist_ok=True)
    models_dir.mkdir(parents=True, exist_ok=True)

    df = pd.read_parquet(args.input)
    df = _normalize_raw_columns(df)
    _validate_raw_input(df)

    print(f"Chargement de {len(df)} transactions brutes.")
    print(f"Colonnes détectées : {list(df.columns)}")

    df_train, df_val, df_test = temporal_split(
        df,
        train_ratio=args.train_ratio,
        val_ratio=args.val_ratio,
        time_col="TX_Time",
    )

    save_split_report(
        df_train,
        df_val,
        df_test,
        output_path=reports_dir / "split_report.json",
    )

    tree_pipe = create_tree_pipeline()
    catboost_pipe = create_catboost_pipeline()
    dl_pipe = create_dl_pipeline("robust")

    fp_tree = FraudFeaturePipeline(tree_pipe, name="tree")
    fp_tree.fit(df_train)

    fp_cat = FraudFeaturePipeline(catboost_pipe, name="catboost")
    fp_cat.fit(df_train)

    fp_dl = FraudFeaturePipeline(dl_pipe, name="dl_robust")
    fp_dl.fit(df_train)

    for split_name, df_split in [
        ("train", df_train),
        ("val", df_val),
        ("test", df_test),
    ]:
        X_tree = fp_tree.transform(df_split)
        pd.DataFrame(X_tree).to_parquet(
            parquet_dir / f"{split_name}_tree.parquet",
            index=False,
        )

        X_cat = fp_cat.transform(df_split)
        pd.DataFrame(X_cat).to_parquet(
            parquet_dir / f"{split_name}_catboost.parquet",
            index=False,
        )

        X_dl = fp_dl.transform(df_split)
        pd.DataFrame(X_dl).to_parquet(
            parquet_dir / f"{split_name}_dl_robust.parquet",
            index=False,
        )

        y_split = df_split["fraud"]
        y_split.to_frame().to_parquet(
            parquet_dir / f"y_{split_name}.parquet",
            index=False,
        )

    fp_tree.save(models_dir / "pipeline_tree.joblib")
    fp_cat.save(models_dir / "pipeline_catboost.joblib")
    fp_dl.save(models_dir / "pipeline_dl_robust.joblib")

    print("Prétraitement terminé.")
    print(f"Fichiers parquet traités : {parquet_dir}")
    print(f"Rapports : {reports_dir}")
    print(f"Pipelines de prétraitement sauvegardés : {models_dir}")


if __name__ == "__main__":
    main()