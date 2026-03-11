"""
Wrapper simple pour exécuter le pipeline d'entraînement complet avec des paramètres par défaut.
Appelle la sous-commande `train` du CLI.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = PROJECT_ROOT / "config" / "model_config.json"
DEFAULT_DATA_DIR = PROJECT_ROOT / "artifacts" / "exported_parquet"
DEFAULT_OUT_DIR = PROJECT_ROOT / "artifacts" / "exported_models"
DEFAULT_ONNX_DIR = PROJECT_ROOT / "artifacts" / "exported_onnx"
DEFAULT_LOG_FILE = PROJECT_ROOT / "artifacts" / "logs" / "training.log"


def main() -> None:
    parser = argparse.ArgumentParser(description="Exécute l'entraînement du modèle.")
    parser.add_argument(
        "--config",
        default=str(DEFAULT_CONFIG),
        help="Chemin vers le JSON de configuration du modèle",
    )
    parser.add_argument(
        "--data-dir",
        default=str(DEFAULT_DATA_DIR),
        help="Répertoire contenant les fichiers parquet prétraités",
    )
    parser.add_argument(
        "--out",
        default=str(DEFAULT_OUT_DIR),
        help="Répertoire de sortie pour les modèles entraînés",
    )
    parser.add_argument(
        "--export-onnx",
        action="store_true",
        help="Exporter les modèles vers ONNX après l'entraînement",
    )
    parser.add_argument(
        "--onnx-dir",
        default=str(DEFAULT_ONNX_DIR),
        help="Répertoire pour les exportations ONNX",
    )
    parser.add_argument(
        "--log",
        default=str(DEFAULT_LOG_FILE),
        help="Chemin du fichier de log",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        help="Niveau de journalisation (logging)",
    )
    args = parser.parse_args()

    cmd = [
        sys.executable,
        "-m",
        "ml_pipeline.cli.main",
        "train",
        "--config",
        args.config,
        "--data-dir",
        args.data_dir,
        "--out",
        args.out,
        "--log-level",
        args.log_level,
    ]

    if args.log:
        cmd.extend(["--log", args.log])

    if args.export_onnx:
        cmd.append("--export-onnx")
        cmd.extend(["--onnx-dir", args.onnx_dir])

    print("Exécution :", " ".join(cmd))
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()