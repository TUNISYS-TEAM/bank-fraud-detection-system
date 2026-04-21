"""
Logic for analyzing dataset files (CSV/Parquet).
Extracted from legacy analyze_dataset.py script.
"""

from __future__ import annotations
import logging
from pathlib import Path
from typing import Any, Dict, Optional, Union

import numpy as np
import pandas as pd

logger = logging.getLogger(__name__)


def analyze_dataset_file(
    file_path: Union[str, Path],
    sample_rows: int = 5,
    max_rows: Optional[int] = 10000,
) -> Dict[str, Any]:
    """
    Analyse un fichier de données et retourne un dictionnaire avec :
        - columns : liste des informations par colonne
        - statistics : statistiques globales
        - suggestedFeatures : liste des colonnes candidates
        - suggestedTarget : colonne cible détectée
        - sampleRows : nombre de lignes d'aperçu
        - preview : liste des premières lignes
    """
    file_path = Path(file_path)
    if not file_path.exists():
        raise FileNotFoundError(f"Fichier introuvable : {file_path}")

    logger.info(f"Analyse du fichier : {file_path}")
    
    # Lecture du fichier (supporte CSV et Parquet)
    if file_path.suffix.lower() == '.parquet':
        df = pd.read_parquet(file_path)
        logger.info(f"Fichier Parquet chargé : {len(df)} lignes")
    else:
        # CSV : on lit avec détection automatique du séparateur
        df = pd.read_csv(file_path, sep=None, engine='python', on_bad_lines='warn')
        logger.info(f"Fichier CSV chargé : {len(df)} lignes")

    total_rows = len(df)
    
    # DÉTECTION DES DOUBLONS
    duplicate_count = df.duplicated().sum()
    duplicate_rate = (duplicate_count / total_rows * 100) if total_rows > 0 else 0
    
    # VALEURS MANQUANTES
    total_missing = df.isna().sum().sum()
    
    # ANALYSE DES COLONNES
    # Si max_rows est spécifié, on prend un échantillon pour les stats
    if max_rows is not None and max_rows < total_rows:
        sample_df = df.sample(n=max_rows, random_state=42)
        analyzed_rows = max_rows
        logger.info(f"Échantillon de {max_rows} lignes utilisé pour les statistiques")
    else:
        sample_df = df
        analyzed_rows = total_rows

    columns_info = []
    suggested_features = []
    suggested_target = None
    fraud_rate = 0.0

    for col in sample_df.columns:
        col_data = sample_df[col].dropna()
        null_ratio = float(sample_df[col].isna().mean())
        unique_count = col_data.nunique()

        # Déterminer le type
        if pd.api.types.is_numeric_dtype(col_data):
            if unique_count == 2 and set(col_data.unique()) <= {0, 1}:
                col_type = "BINARY"
            else:
                col_type = "NUMERIC"
                suggested_features.append(col)
        elif pd.api.types.is_datetime64_any_dtype(col_data):
            col_type = "DATE"
        else:
            col_type = "CATEGORICAL"
            suggested_features.append(col)

        # Statistiques selon le type
        stats: Dict[str, Any] = {}
        if col_type in ("NUMERIC", "BINARY"):
            if not col_data.empty:
                stats = {
                    "min": float(col_data.min()),
                    "max": float(col_data.max()),
                    "mean": float(col_data.mean()),
                    "std": float(col_data.std()) if len(col_data) > 1 else None,
                    "p25": float(col_data.quantile(0.25)),
                    "p50": float(col_data.quantile(0.5)),
                    "p75": float(col_data.quantile(0.75)),
                }
        elif col_type == "CATEGORICAL":
            value_counts = col_data.value_counts().head(5)
            stats = {
                "uniqueValues": unique_count,
                "topValues": value_counts.to_dict(),
            }

        # Détection de la colonne cible
        if col_type == "BINARY" and suggested_target is None:
            lower = col.lower()
            if any(keyword in lower for keyword in ("fraud", "target", "label", "class", "is_fraud")):
                suggested_target = col
                if col in df.columns:
                    fraud_rate = float(df[col].mean()) * 100

        columns_info.append({
            "name": col,
            "type": col_type,
            "uniqueCount": unique_count,
            "nullRatio": null_ratio,
            "stats": stats,
        })

    # Statistiques globales enrichies
    global_stats = {
        "rowCount": total_rows,
        "columnCount": len(sample_df.columns),
        "fraudRate": round(fraud_rate, 2),  
        "duplicateCount": int(duplicate_count),
        "duplicateRate": round(duplicate_rate, 2),
        "totalMissingValues": int(total_missing),
        "analyzedRows": analyzed_rows,
        "isPartialAnalysis": analyzed_rows < total_rows,
        "memoryUsageMb": round(df.memory_usage(deep=True).sum() / (1024 * 1024), 2),
    }

    # Aperçu
    preview = df.head(sample_rows).replace({np.nan: None}).to_dict(orient='records')
    logger.info(f"Analyse terminée : {len(columns_info)} colonnes analysées")

    return {
        "columns": columns_info,
        "statistics": global_stats,
        "suggestedFeatures": suggested_features,
        "suggestedTarget": suggested_target,
        "sampleRows": len(preview),
        "preview": preview,
    }