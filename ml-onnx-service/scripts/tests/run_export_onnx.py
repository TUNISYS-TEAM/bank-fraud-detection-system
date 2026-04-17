"""
Exporte un modèle entraîné vers ONNX en utilisant le CLI du projet.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "artifacts" / "exported_onnx"


def main() -> None:
    parser = argparse.ArgumentParser(description="Exporte un modèle entraîné vers ONNX.")
    parser.add_argument(
        "--model-type",
        required=True,
        choices=["lightgbm", "xgboost", "random_forest", "catboost", "autoencoder"],
        help="Type de modèle à exporter",
    )
    parser.add_argument(
        "--model-path",
        required=True,
        help="Chemin vers le fichier .joblib du modèle entraîné",
    )
    parser.add_argument(
        "--pipeline-path",
        help="Chemin vers le fichier .joblib du pipeline de prétraitement ajusté (optionnel)",
    )
    parser.add_argument(
        "--output-dir",
        default=str(DEFAULT_OUTPUT_DIR),
        help="Répertoire où le modèle ONNX sera sauvegardé",
    )
    parser.add_argument(
        "--no-verify",
        action="store_true",
        help="Passer la vérification d'équivalence ONNX",
    )
    args = parser.parse_args()

    cmd = [
        sys.executable,
        "-m",
        "ml_pipeline.cli.main",
        "export-onnx",
        "--model-type",
        args.model_type,
        "--model-path",
        args.model_path,
        "--output-dir",
        args.output_dir,
    ]

    if args.pipeline_path:
        cmd.extend(["--pipeline-path", args.pipeline_path])

    if args.no_verify:
        cmd.append("--no-verify")

    print("Exécution :", " ".join(cmd))
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()