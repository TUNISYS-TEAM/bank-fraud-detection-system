from __future__ import annotations

import argparse
import sys
import json
import logging
from pathlib import Path

from ml_pipeline.pipeline.onnx_exporter import main as export_main
from ml_pipeline.training.train_models import main as train_main
from ml_pipeline.training.analyze_dataset import analyze_dataset_file
from ml_pipeline.transformers.behavioral.client_behavior import ClientBehaviorTransformer
import pandas as pd

from scripts.train_random_forest import main as train_random_forest_main
from scripts.train_xgboost import main as train_xgboost_main
from scripts.train_lightgbm import main as train_lightgbm_main
from scripts.train_balanced_forest import main as train_balanced_forest_main
from scripts.train_hybrid_ensemble import main as train_hybrid_ensemble_main
from scripts.train_autoencoder import main as train_autoencoder_main
from scripts.train_ecod import main as train_ecod_main
from scripts.train_copod import main as train_copod_main



def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ml-pipeline",
        description="Interface en ligne de commande (CLI) du pipeline ML de détection de fraude",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Niveau de journalisation (logging)",
    )

    subparsers = parser.add_subparsers(dest="command", required=True, help="Available commands")

    # ---- train ----
    train_parser = subparsers.add_parser("train", help="Entraîne tous les modèles")
    train_parser.add_argument("--config", default="src/ml_pipeline/config/model_config.json")
    train_parser.add_argument("--data-dir", default="artifacts/exported_parquet")
    train_parser.add_argument("--out", default="artifacts/exported_models")
    train_parser.add_argument("--export-onnx", action="store_true")
    train_parser.add_argument("--onnx-dir", default="artifacts/exported_onnx")
    train_parser.add_argument("--log", default=None)
    train_parser.add_argument("--level", default="INFO")

    # ---- train-random-forest ----
    rf_parser = subparsers.add_parser(
        "train-random-forest",
        help="Entraîne un modèle Random Forest pour le backend Java"
    )
    rf_parser.add_argument("--dataset-path", required=True)
    rf_parser.add_argument("--dataset-id", required=True)
    rf_parser.add_argument("--job-id", required=True)
    rf_parser.add_argument("--timestamp", required=True) 
    rf_parser.add_argument("--hyperparameters", required=True)
    rf_parser.add_argument("--test-size", type=float, default=0.2)
    rf_parser.add_argument("--random-seed", type=int, default=42)

    # ---- train-balanced-random-forest ----
    brf_parser = subparsers.add_parser(
        "train-balanced-random-forest",
        help="Entraîne un modèle Balanced Random Forest (imblearn)"
    )
    brf_parser.add_argument("--dataset-path", required=True)
    brf_parser.add_argument("--dataset-id", required=True)
    brf_parser.add_argument("--job-id", required=True)
    brf_parser.add_argument("--timestamp", required=True)
    brf_parser.add_argument("--hyperparameters", required=True)
    brf_parser.add_argument("--test-size", type=float, default=0.2)
    brf_parser.add_argument("--random-seed", type=int, default=42)


    # ---- train-xgboost ----
    xgb_parser = subparsers.add_parser(
        "train-xgboost",
        help="Entraîne un modèle XGBoost pour le backend Java"
    )
    xgb_parser.add_argument("--dataset-path", required=True)
    xgb_parser.add_argument("--dataset-id", required=True)
    xgb_parser.add_argument("--job-id", required=True)
    xgb_parser.add_argument("--timestamp", required=True)
    xgb_parser.add_argument("--hyperparameters", required=True)
    xgb_parser.add_argument("--test-size", type=float, default=0.2)
    xgb_parser.add_argument("--random-seed", type=int, default=42)

    # ---- train-lightgbm ----
    lgbm_parser = subparsers.add_parser(
        "train-lightgbm",
        help="Entraîne un modèle LightGBM pour le backend Java"
    )
    lgbm_parser.add_argument("--dataset-path", required=True)
    lgbm_parser.add_argument("--dataset-id", required=True)
    lgbm_parser.add_argument("--job-id", required=True)
    lgbm_parser.add_argument("--timestamp", required=True)
    lgbm_parser.add_argument("--hyperparameters", required=True)
    lgbm_parser.add_argument("--test-size", type=float, default=0.2)
    lgbm_parser.add_argument("--random-seed", type=int, default=42)

    # ---- train-hybrid-ensemble ----
    hybrid_parser = subparsers.add_parser(
        "train-hybrid-ensemble",
        help="Entraîne un modèle hybride (Supervisé + PyOD + Meta-learner)"
    )
    hybrid_parser.add_argument("--dataset-path", required=True)
    hybrid_parser.add_argument("--dataset-id", required=True)
    hybrid_parser.add_argument("--job-id", required=True)
    hybrid_parser.add_argument("--timestamp", required=True)
    hybrid_parser.add_argument("--hyperparameters", required=True)
    hybrid_parser.add_argument("--test-size", type=float, default=0.2)
    hybrid_parser.add_argument("--random-seed", type=int, default=42)

    # ---- train-autoencoder ----
    ae_parser = subparsers.add_parser(
        "train-autoencoder",
        help="Entraîne un modèle Autoencoder pour la détection d'anomalies"
    )
    ae_parser.add_argument("--dataset-path", required=True)
    ae_parser.add_argument("--dataset-id", required=True)
    ae_parser.add_argument("--job-id", required=True)
    ae_parser.add_argument("--timestamp", required=True)
    ae_parser.add_argument("--hyperparameters", required=True)
    ae_parser.add_argument("--test-size", type=float, default=0.2)
    ae_parser.add_argument("--random-seed", type=int, default=42)

    # ---- train-ecod ----
    ecod_parser = subparsers.add_parser(
        "train-ecod",
        help="Entraîne un modèle ECOD (PyOD)"
    )
    ecod_parser.add_argument("--dataset-path", required=True)
    ecod_parser.add_argument("--dataset-id", required=True)
    ecod_parser.add_argument("--job-id", required=True)
    ecod_parser.add_argument("--timestamp", required=True)
    ecod_parser.add_argument("--hyperparameters", required=True)
    ecod_parser.add_argument("--test-size", type=float, default=0.2)
    ecod_parser.add_argument("--random-seed", type=int, default=42)

    # ---- train-copod ----
    copod_parser = subparsers.add_parser(
        "train-copod",
        help="Entraîne un modèle COPOD (PyOD)"
    )
    copod_parser.add_argument("--dataset-path", required=True)
    copod_parser.add_argument("--dataset-id", required=True)
    copod_parser.add_argument("--job-id", required=True)
    copod_parser.add_argument("--timestamp", required=True)
    copod_parser.add_argument("--hyperparameters", required=True)
    copod_parser.add_argument("--test-size", type=float, default=0.2)
    copod_parser.add_argument("--random-seed", type=int, default=42)

    # ---- export-onnx ----
    export_parser = subparsers.add_parser("export-onnx", help="Exporte le modèle au format ONNX")
    export_parser.add_argument(
        "--model-type",
        required=True,
        choices=["lightgbm", "xgboost", "random_forest", "catboost", "autoencoder"],
    )
    export_parser.add_argument("--model-path", required=True)
    export_parser.add_argument("--pipeline-path", default=None)
    export_parser.add_argument("--output-dir", default="artifacts/exported_onnx")
    export_parser.add_argument("--no-verify", action="store_true")

    # ---- analyze-dataset ----
    analyze_parser = subparsers.add_parser(
        "analyze-dataset",
        help="Analyse un fichier CSV/Parquet et renvoie les statistiques des colonnes (types, suggestions, etc.)"
    )
    analyze_parser.add_argument(
        "--dataset-path",
        required=True,
        help="Chemin vers le fichier de données (CSV ou Parquet)"
    )
    analyze_parser.add_argument(
        "--out",
        default=None,
        help="(Optionnel) Chemin pour sauvegarder le résultat JSON (sinon seulement stdout)"
    )
    analyze_parser.add_argument(
        "--sample-rows",
        type=int,
        default=5,
        help="Nombre de lignes à inclure dans l'aperçu (défaut: 5)"
    )
    analyze_parser.add_argument(
        "--max-rows",
        type=int,
        default=10000,
        help="Nombre maximum de lignes à analyser pour les statistiques (0 = toutes, défaut: 10000)"
    )

    # ---- extract-behavior ----
    extract_parser = subparsers.add_parser(
        "extract-behavior",
        help="Extrait les features comportementales client pour un dataset de transactions"
    )
    extract_parser.add_argument(
        "--input",
        required=True,
        help="Chemin du fichier d'entrée (CSV ou Parquet)"
    )
    extract_parser.add_argument(
        "--output",
        help="Chemin du fichier de sortie (Parquet recommandé)"
    )
    extract_parser.add_argument(
        "--dataset-id",
        required=True,
        help="UUID du dataset (pour traçabilité)"
    )
    extract_parser.add_argument(
        "--job-id",
        required=True,
        help="UUID du job (pour traçabilité)"
    )
    extract_parser.add_argument(
        "--use-fitted-history",
        action="store_true",
        help="Utiliser l'historique client pré-entraîné (si disponible)"
    )
    extract_parser.add_argument(
        "--history-path",
        help="Chemin vers le fichier d'historique client (JSON) si --use-fitted-history est activé"
    )

    # ---- convert-to-parquet ----
    convert_parser = subparsers.add_parser(
        "convert-to-parquet",
        help="Convertit un fichier CSV au format Parquet"
    )
    convert_parser.add_argument(
        "--input",
        required=True,
        help="Chemin du fichier d'entrée (CSV)"
    )
    convert_parser.add_argument(
        "--output",
        required=True,
        help="Chemin du fichier de sortie (Parquet)"
    )

    # ---- convert-to-csv ----
    convert_csv_parser = subparsers.add_parser(
        "convert-to-csv",
        help="Convertit un fichier Parquet au format CSV"
    )
    convert_csv_parser.add_argument(
        "--input",
        required=True,
        help="Chemin du fichier d'entrée (Parquet)"
    )
    convert_csv_parser.add_argument(
        "--output",
        required=True,
        help="Chemin du fichier de sortie (CSV)"
    )

    # ---- validate ----
    validate_parser = subparsers.add_parser("validate", help="Exécute les vérifications de validation")
    validate_parser.add_argument("--data-dir", default="artifacts/exported_parquet")

    return parser


def extract_behavior_command(args):
    """Implémentation de la commande extract-behavior."""
    try:
        input_path = Path(args.input)
        dataset_id = args.dataset_id
        job_id = args.job_id

        logging.info("Extraction des features comportementales - dataset=%s job=%s", dataset_id, job_id)
        logging.info("Fichier d'entrée: %s", input_path)

        # 1. Charger les données
        if input_path.suffix.lower() == '.parquet':
            df = pd.read_parquet(input_path)
        else:
            df = pd.read_csv(input_path)
            if 'TX_Time' in df.columns:
                df['TX_Time'] = pd.to_datetime(df['TX_Time'], errors='coerce')

        logging.info("Dataset chargé : %d lignes, %d colonnes", len(df), len(df.columns))

        # 2. Vérifier les colonnes requises
        required = {'Account_Id', 'TX_Time', 'TX_Amount', 'Customer_Postal_Code', 'Channel', 'category'}
        missing = required - set(df.columns)
        if missing:
            raise ValueError(f"Colonnes manquantes : {missing}")

        # 3. Initialiser le transformer
        transformer = ClientBehaviorTransformer()

        # 4. Charger l'historique si demandé
        if args.use_fitted_history and args.history_path:
            from collections import Counter
            history_path = Path(args.history_path)
            if history_path.exists():
                with open(history_path, 'r', encoding='utf-8') as f:
                    client_stats = json.load(f)
                    for acc, state in client_stats.items():
                        state['region_counter'] = Counter(state['region_counter'])
                        state['channel_counter'] = Counter(state['channel_counter'])
                        state['category_counter'] = Counter(state['category_counter'])
                    transformer.client_stats_ = client_stats
                logging.info("Historique client chargé depuis %s", args.history_path)
            else:
                logging.warning("Fichier d'historique non trouvé: %s", args.history_path)

        # 5. Appliquer le transformer
        if not args.use_fitted_history:
            logging.info("Mode strict as-of : fit sur les données fournies")
            transformer.fit(df)
            df_features = transformer.transform(df, use_fitted_history=False)
        else:
            logging.info("Mode avec historique pré-entraîné")
            df_features = transformer.transform(df, use_fitted_history=True)

        logging.info("Features extraites : %d colonnes", len(df_features.columns))

        # 6. Fusionner avec les données originales
        for col in df_features.columns:
            if col not in df.columns:
                df[col] = df_features[col]
            else:
                logging.warning("Colonne %s déjà présente, ignorée", col)

        # 7. Déterminer le chemin de sortie
        if args.output:
            output_path = Path(args.output)
        else:
            output_path = input_path.parent / f"enriched_{dataset_id}.parquet"

        output_path.parent.mkdir(parents=True, exist_ok=True)
        df.to_parquet(output_path, index=False)
        logging.info("Fichier enrichi sauvegardé : %s (%d Mo)",
                     output_path, output_path.stat().st_size / (1024 * 1024))

        stats = {
            "status": "success",
            "original_rows": len(df),
            "accounts": int(df["Account_Id"].nunique()),
            "columns_before": list(df_features.columns),
            "all_columns": list(df.columns),
            "output_file": str(output_path),
            "dataset_id": dataset_id,
            "job_id": job_id,
            "columns_added": list(df_features.columns)
        }

        print(json.dumps(stats, default=str))
        logging.info("Extraction terminée avec succès")

    except Exception as e:
        error_result = {
            "status": "error",
            "error": str(e),
            "dataset_id": args.dataset_id if hasattr(args, 'dataset_id') else None,
            "job_id": args.job_id if hasattr(args, 'job_id') else None
        }
        print(json.dumps(error_result))
        logging.exception("Erreur lors de l'extraction des features comportementales")
        sys.exit(1)


def convert_to_parquet_command(args):
    """Implémentation de la commande convert-to-parquet."""
    try:
        input_path = Path(args.input)
        output_path = Path(args.output)

        logging.info("Conversion CSV vers Parquet - input=%s output=%s", input_path, output_path)

        if not input_path.exists():
            raise FileNotFoundError(f"Fichier d'entrée non trouvé: {input_path}")

        output_path.parent.mkdir(parents=True, exist_ok=True)

        logging.info("Lecture du fichier CSV: %s", input_path)
        df = pd.read_csv(input_path)
        logging.info("CSV chargé: %d lignes, %d colonnes", len(df), len(df.columns))

        logging.info("Conversion en Parquet...")
        df.to_parquet(output_path, index=False, compression='snappy')

        if output_path.exists():
            file_size_mb = output_path.stat().st_size / (1024 * 1024)
            logging.info("Conversion réussie: %s (%.2f MB)", output_path, file_size_mb)

            result = {
                "status": "success",
                "input": str(input_path),
                "output": str(output_path),
                "rows": len(df),
                "columns": len(df.columns),
                "size_mb": round(file_size_mb, 2)
            }
            print(json.dumps(result))
        else:
            raise RuntimeError("Le fichier de sortie n'a pas été créé")

    except Exception as e:
        error_result = {
            "status": "error",
            "error": str(e),
            "input": args.input if hasattr(args, 'input') else None,
            "output": args.output if hasattr(args, 'output') else None
        }
        print(json.dumps(error_result))
        logging.exception("Erreur lors de la conversion CSV vers Parquet")
        sys.exit(1)


def convert_to_csv_command(args):
    """Implémentation de la commande convert-to-csv."""
    try:
        input_path = Path(args.input)
        output_path = Path(args.output)

        logging.info("Conversion Parquet vers CSV - input=%s output=%s", input_path, output_path)

        if not input_path.exists():
            raise FileNotFoundError(f"Fichier d'entrée non trouvé: {input_path}")

        output_path.parent.mkdir(parents=True, exist_ok=True)

        logging.info("Lecture du fichier Parquet: %s", input_path)
        df = pd.read_parquet(input_path)
        logging.info("Parquet chargé: %d lignes, %d colonnes", len(df), len(df.columns))

        logging.info("Conversion en CSV...")
        df.to_csv(output_path, index=False)

        if output_path.exists():
            file_size_mb = output_path.stat().st_size / (1024 * 1024)
            logging.info("Conversion réussie: %s (%.2f MB)", output_path, file_size_mb)

            result = {
                "status": "success",
                "input": str(input_path),
                "output": str(output_path),
                "rows": len(df),
                "columns": len(df.columns),
                "size_mb": round(file_size_mb, 2)
            }
            print(json.dumps(result))
        else:
            raise RuntimeError("Le fichier de sortie n'a pas été créé")

    except Exception as e:
        error_result = {
            "status": "error",
            "error": str(e),
            "input": args.input if hasattr(args, 'input') else None,
            "output": args.output if hasattr(args, 'output') else None
        }
        print(json.dumps(error_result))
        logging.exception("Erreur lors de la conversion Parquet vers CSV")
        sys.exit(1)


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format='%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
        stream=sys.stderr
    )

    if args.command == "train":
        sys.argv = ["train_models"]
        for key, value in vars(args).items():
            if key == "command" or value is None:
                continue
            if isinstance(value, bool):
                if value:
                    sys.argv.append(f"--{key.replace('_', '-')}")
            else:
                sys.argv.extend([f"--{key.replace('_', '-')}", str(value)])
        train_main()

    elif args.command == "train-random-forest":
        sys.argv = [
            "train_random_forest",
            "--dataset-path", args.dataset_path,
            "--dataset-id", args.dataset_id,
            "--job-id", args.job_id,
            "--timestamp", args.timestamp,  # <-- ADDED
            "--hyperparameters", args.hyperparameters,
            "--test-size", str(args.test_size),
            "--random-seed", str(args.random_seed),
        ]
        train_random_forest_main()

    elif args.command == "train-xgboost":
        sys.argv = [
            "train_xgboost",
            "--dataset-path", args.dataset_path,
            "--dataset-id", args.dataset_id,
            "--job-id", args.job_id,
            "--timestamp", args.timestamp,
            "--hyperparameters", args.hyperparameters,
            "--test-size", str(args.test_size),
            "--random-seed", str(args.random_seed),
        ]
        train_xgboost_main()

    elif args.command == "train-lightgbm":
        sys.argv = [
            "train_lightgbm",
            "--dataset-path", args.dataset_path,
            "--dataset-id", args.dataset_id,
            "--job-id", args.job_id,
            "--timestamp", args.timestamp,
            "--hyperparameters", args.hyperparameters,
            "--test-size", str(args.test_size),
            "--random-seed", str(args.random_seed),
        ]
        train_lightgbm_main()

    elif args.command == "train-balanced-random-forest":
        sys.argv = [
            "train_balanced_forest",
            "--dataset-path", args.dataset_path,
            "--dataset-id", args.dataset_id,
            "--job-id", args.job_id,
            "--timestamp", args.timestamp,
            "--hyperparameters", args.hyperparameters,
            "--test-size", str(args.test_size),
            "--random-seed", str(args.random_seed),
        ]
        train_balanced_forest_main()

    elif args.command == "train-hybrid-ensemble":
        sys.argv = [
            "train_hybrid_ensemble",
            "--dataset-path", args.dataset_path,
            "--dataset-id", args.dataset_id,
            "--job-id", args.job_id,
            "--timestamp", args.timestamp,
            "--hyperparameters", args.hyperparameters,
            "--test-size", str(args.test_size),
            "--random-seed", str(args.random_seed),
        ]
        train_hybrid_ensemble_main()

    elif args.command == "train-autoencoder":
        sys.argv = [
            "train_autoencoder",
            "--dataset-path", args.dataset_path,
            "--dataset-id", args.dataset_id,
            "--job-id", args.job_id,
            "--timestamp", args.timestamp,
            "--hyperparameters", args.hyperparameters,
            "--test-size", str(args.test_size),
            "--random-seed", str(args.random_seed),
        ]
        train_autoencoder_main()

    elif args.command == "train-ecod":
        sys.argv = [
            "train_ecod",
            "--dataset-path", args.dataset_path,
            "--dataset-id", args.dataset_id,
            "--job-id", args.job_id,
            "--timestamp", args.timestamp,
            "--hyperparameters", args.hyperparameters,
            "--test-size", str(args.test_size),
            "--random-seed", str(args.random_seed),
        ]
        train_ecod_main()

    elif args.command == "train-copod":
        sys.argv = [
            "train_copod",
            "--dataset-path", args.dataset_path,
            "--dataset-id", args.dataset_id,
            "--job-id", args.job_id,
            "--timestamp", args.timestamp,
            "--hyperparameters", args.hyperparameters,
            "--test-size", str(args.test_size),
            "--random-seed", str(args.random_seed),
        ]
        train_copod_main()


    elif args.command == "export-onnx":
        sys.argv = [
            "onnx_exporter",
            "--model-type", args.model_type,
            "--model-path", args.model_path,
            "--output-dir", args.output_dir,
        ]
        if args.pipeline_path:
            sys.argv.extend(["--pipeline-path", args.pipeline_path])
        if args.no_verify:
            sys.argv.append("--no-verify")
        export_main()

    elif args.command == "analyze-dataset":
        try:
            result = analyze_dataset_file(
                file_path=args.dataset_path,
                sample_rows=args.sample_rows,
                max_rows=args.max_rows if args.max_rows > 0 else None
            )
            json_output = json.dumps(result, indent=2, default=str)
            print(json_output)

            if args.out:
                out_path = Path(args.out)
                out_path.parent.mkdir(parents=True, exist_ok=True)
                out_path.write_text(json_output, encoding="utf-8")
                print(f"Résultat également sauvegardé dans {out_path}", file=sys.stderr)
        except Exception as e:
            error_result = {"error": str(e)}
            print(json.dumps(error_result))
            logging.exception("Erreur lors de l'analyse")
            sys.exit(1)

    elif args.command == "extract-behavior":
        extract_behavior_command(args)

    elif args.command == "convert-to-parquet":
        convert_to_parquet_command(args)

    elif args.command == "convert-to-csv":
        convert_to_csv_command(args)

    elif args.command == "validate":
        print("Validation CLI not fully wired yet.", file=sys.stderr)
        print("Use ml_pipeline.training.validate_pipeline directly for now.", file=sys.stderr)


if __name__ == "__main__":
    main()