"""
Script de nettoyage - Suppression des lignes avec valeurs manquantes
"""

import pandas as pd
import json
import sys
from pathlib import Path
from datetime import datetime
import argparse

UPLOAD_DIR = Path("/app/data").resolve()


def validate_path(input_path: str) -> Path:
    """Vérifie que le chemin est dans le répertoire autorisé (anti path traversal)."""
    file_path = Path(input_path).resolve()
    if not str(file_path).startswith(str(UPLOAD_DIR)):
        raise ValueError("Accès refusé : chemin hors du répertoire autorisé.")
    if not file_path.exists():
        raise FileNotFoundError("Fichier introuvable.")
    return file_path


def clean_with_immediate_null_removal(
    input_path: str, 
    dataset_id: str, 
    job_id: str,
    analysis_results: dict = None
) -> dict:
    """
    Nettoie un fichier CSV en supprimant IMMÉDIATEMENT les lignes
    dès qu'une colonne a des valeurs manquantes (null ratio > 0).
    """
    start_time = datetime.now()
    result = {
        'job_id': job_id,
        'dataset_id': dataset_id,
        'start_time': start_time.isoformat(),
        'input_file': Path(input_path).name,
        'analysis_used': analysis_results is not None
    }

    try:
        # 1. Validation du chemin
        file_path = validate_path(input_path)

        # 2. Lecture du CSV (en chunks pour les gros fichiers)
        df_sample = pd.read_csv(file_path, nrows=1)
        all_columns = list(df_sample.columns)

        # 3. Récupérer les null ratios depuis l'analyse
        null_thresholds = {}
        important_columns = set()
        
        if analysis_results:
            # Extraire les nullRatios
            if 'nullRatios' in analysis_results:
                null_thresholds = analysis_results.get('nullRatios', {})
            elif 'columns' in analysis_results:
                for col_info in analysis_results.get('columns', []):
                    if 'name' in col_info and 'nullRatio' in col_info:
                        null_thresholds[col_info['name']] = col_info['nullRatio']
            
            # Colonnes importantes (features + target)
            suggested_features = analysis_results.get('suggestedFeatures', [])
            suggested_target = analysis_results.get('suggestedTarget')
            
            important_columns = set(suggested_features or [])
            if suggested_target:
                important_columns.add(suggested_target)
        else:
            # Si pas d'analyse, on doit calculer les null ratios nous-mêmes
            df_temp = pd.read_csv(file_path, low_memory=False)
            null_ratios_calc = df_temp.isna().mean().to_dict()
            for col, ratio in null_ratios_calc.items():
                if ratio > 0:
                    null_thresholds[col] = ratio

        # 4. Filtrer les colonnes qui nous intéressent (celles avec null ratio > 0)
        columns_with_nulls = [
            col for col in null_thresholds.keys()
            if col in all_columns and null_thresholds[col] > 0
        ]

        # 5. Lecture et nettoyage PROGRESSIF du fichier
        original_rows = 0
        chunks = []
        chunk_size = 10000

        # Lire le fichier par chunks pour optimiser la mémoire
        for chunk in pd.read_csv(file_path, chunksize=chunk_size, low_memory=False):
            chunk_rows = len(chunk)
            original_rows += chunk_rows
            
            # Étape 1: Supprimer les doublons dans ce chunk
            chunk = chunk.drop_duplicates()
            
            # Étape 2: Pour CHAQUE colonne avec des nulls, supprimer IMMÉDIATEMENT les lignes
            for col in columns_with_nulls:
                if col in chunk.columns:
                    # Supprimer les lignes avec null dans cette colonne
                    chunk = chunk[chunk[col].notna()]
            
            # Garder le chunk nettoyé s'il n'est pas vide
            if len(chunk) > 0:
                chunks.append(chunk)

        # 6. Combiner tous les chunks nettoyés
        if chunks:
            df_cleaned = pd.concat(chunks, ignore_index=True)
            cleaned_rows = len(df_cleaned)
        else:
            df_cleaned = pd.DataFrame()
            cleaned_rows = 0

        # 7. Statistiques finales
        null_rows_removed = original_rows - cleaned_rows
        
        # Compter la fraude si la colonne existe
        fraud_count_after = 0
        fraud_rate = 0
        if 'fraud' in df_cleaned.columns and cleaned_rows > 0:
            fraud_count_after = int(df_cleaned['fraud'].sum())
            fraud_rate = (fraud_count_after / cleaned_rows * 100)
        
        reduction_percent = ((original_rows - cleaned_rows) / original_rows * 100) if original_rows > 0 else 0

        # 8. Sauvegarde en Parquet
        output_dir = Path('/app/output')
        output_dir.mkdir(parents=True, exist_ok=True)

        output_filename = f"cleaned_{dataset_id}.parquet"
        output_path = output_dir / output_filename
        
        if cleaned_rows > 0:
            df_cleaned.to_parquet(output_path, index=False, compression='snappy')
        else:
            # Créer un fichier vide avec les bonnes colonnes
            pd.DataFrame(columns=all_columns).to_parquet(output_path, index=False)

        # 9. Statistiques détaillées
        cleaning_stats = {
            'original_rows': original_rows,
            'original_columns': len(all_columns),
            'cleaned_rows': cleaned_rows,
            'cleaned_columns': len(df_cleaned.columns) if cleaned_rows > 0 else 0,
            'duplicates_removed': 0,
            'null_rows_removed': int(null_rows_removed),
            'columns_with_nulls': columns_with_nulls,
            'columns_with_nulls_count': len(columns_with_nulls),
            'reduction_percent': round(reduction_percent, 2),
            'fraud_count_after': fraud_count_after,
            'fraud_rate': round(fraud_rate, 4),
            'processing_chunks': len(chunks)
        }

        result.update({
            'status': 'success',
            'end_time': datetime.now().isoformat(),
            'output_file': output_filename,
            'cleaning_stats': cleaning_stats,
            'columns': list(df_cleaned.columns) if cleaned_rows > 0 else all_columns,
        })

    except Exception as e:
        result.update({
            'status': 'error',
            'end_time': datetime.now().isoformat(),
            'error': str(e),
        })

    return result


def main():
    parser = argparse.ArgumentParser(description='Clean CSV with immediate null removal')
    parser.add_argument('--input', required=True, help='Input CSV file path')
    parser.add_argument('--dataset-id', required=True, help='Dataset UUID')
    parser.add_argument('--job-id', required=True, help='Job ID')
    parser.add_argument('--analysis-json', help='Analysis results JSON file path (optional)')

    args = parser.parse_args()

    # Charger les résultats d'analyse si fournis
    analysis_results = None
    if args.analysis_json:
        try:
            analysis_path = validate_path(args.analysis_json)
            with open(analysis_path, 'r') as f:
                analysis_results = json.load(f)
        except Exception:
            # Silently ignore - will proceed without analysis
            pass

    result = clean_with_immediate_null_removal(
        args.input, 
        args.dataset_id, 
        args.job_id,
        analysis_results
    )
    
    # SEULEMENT le JSON final sur stdout
    print(json.dumps(result))


if __name__ == '__main__':
    main()