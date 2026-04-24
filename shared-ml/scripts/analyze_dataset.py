"""
Wrapper script for dataset analysis.
Maintains the same interface for the Java backend but uses shared_ml logic.
"""

import json
import sys
import argparse
from pathlib import Path

# On tente d'importer la logique du package shared_ml
try:
    from shared_ml.preprocessing.analysis import analyze_dataset_file
except ImportError:
    # Fallback au cas ou le package n'est pas encore installe (utile en dev/docker)
    import sys
    sys.path.append(str(Path(__file__).parent.parent / "src"))
    from shared_ml.preprocessing.analysis import analyze_dataset_file

def main():
    parser = argparse.ArgumentParser(description="Analyse un dataset et retourne du JSON")
    parser.add_argument("file_path", nargs="?", default=None, help="Chemin vers le fichier CSV ou Parquet (positionnel)")
    parser.add_argument("--dataset-path", dest="dataset_path", default=None, help="Chemin vers le fichier CSV ou Parquet (nommé)")
    parser.add_argument("--max-rows", type=int, default=10000, help="Nombre max de lignes a analyser")
    args = parser.parse_args()

    # Accept both positional and named --dataset-path (Java backend uses --dataset-path)
    path = args.dataset_path or args.file_path
    if not path:
        parser.error("Vous devez fournir un chemin de fichier (positionnel ou --dataset-path)")

    try:
        results = analyze_dataset_file(path, max_rows=args.max_rows)
        # On imprime uniquement le JSON sur stdout pour que le backend puisse le parser
        print(json.dumps(results, indent=2))
    except Exception as e:
        # Les erreurs vont sur stderr
        print(f"Erreur lors de l'analyse : {str(e)}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()