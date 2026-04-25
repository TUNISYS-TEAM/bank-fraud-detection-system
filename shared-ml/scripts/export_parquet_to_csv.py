import pandas as pd
import sys
import json
import argparse
from pathlib import Path

"""
Script utilitaire pour exporter un fichier Parquet en CSV.
Utilisé pour le téléchargement des datasets qualifiés depuis le dashboard.
"""

def export_parquet_to_csv(input_path, output_path):
    try:
        # Lecture du fichier Parquet
        df = pd.read_parquet(input_path)
        
        # Exportation en CSV
        # On utilise la virgule comme séparateur par défaut pour une compatibilité maximale
        df.to_csv(output_path, index=False, sep=',')
        
        return {
            "status": "success",
            "rows": len(df),
            "columns": len(df.columns),
            "output_file": Path(output_path).name
        }
    except Exception as e:
        return {
            "status": "error",
            "error": str(e)
        }

def main():
    parser = argparse.ArgumentParser(description='Convert Parquet to CSV for export')
    parser.add_argument('--input', required=True, help='Path to input Parquet file')
    parser.add_argument('--output', required=True, help='Path to output CSV file')
    
    args = parser.parse_args()
    
    result = export_parquet_to_csv(args.input, args.output)
    print(json.dumps(result))

if __name__ == '__main__':
    main()