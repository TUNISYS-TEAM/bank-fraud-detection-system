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

    # ---- convert-to-parquet - NOUVELLE COMMANDE ----
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
            # Pour CSV, essayer de parser TX_Time automatiquement
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
            import json
            from collections import Counter
            history_path = Path(args.history_path)
            if history_path.exists():
                with open(history_path, 'r') as f:
                    # Note: Le chargement complet de l'historique peut être lourd
                    # À adapter selon le format de sérialisation
                    client_stats = json.load(f)
                    # Reconstruire les Counter objects
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
            # Mode strict as-of : fit puis transform
            logging.info("Mode strict as-of : fit sur les données fournies")
            transformer.fit(df)
            df_features = transformer.transform(df, use_fitted_history=False)
        else:
            # Mode avec historique : transform seulement
            logging.info("Mode avec historique pré-entraîné")
            df_features = transformer.transform(df, use_fitted_history=True)

        logging.info("Features extraites : %d colonnes", len(df_features.columns))

        # 6. Fusionner avec les données originales
        # On ajoute les nouvelles colonnes, en évitant les collisions
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
        
        # Créer le répertoire parent si nécessaire
        output_path.parent.mkdir(parents=True, exist_ok=True)
        
        # Sauvegarder au format Parquet (compact et rapide)
        df.to_parquet(output_path, index=False)
        logging.info("Fichier enrichi sauvegardé : %s (%d Mo)", 
                    output_path, output_path.stat().st_size / (1024*1024))

        # 8. Statistiques pour le retour JSON
        stats = {
            "status": "success",
            "original_rows": len(df),
            "accounts": int(df["Account_Id"].nunique()),
            "columns_before": list(df_features.columns),  # seulement les nouvelles
            "all_columns": list(df.columns),
            "output_file": str(output_path),
            "dataset_id": dataset_id,
            "job_id": job_id,
            "columns_added": list(df_features.columns)
        }
        
        # Écrire le JSON sur stdout (pour Java)
        print(json.dumps(stats, default=str))
        
        # Écrire les logs sur stderr
        logging.info("Extraction terminée avec succès")

    except Exception as e:
        # En cas d'erreur, imprimer un JSON d'erreur sur stdout
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
        
        # Vérifier que le fichier d'entrée existe
        if not input_path.exists():
            raise FileNotFoundError(f"Fichier d'entrée non trouvé: {input_path}")
        
        # Créer le répertoire de sortie si nécessaire
        output_path.parent.mkdir(parents=True, exist_ok=True)
        
        # Lire le CSV
        logging.info("Lecture du fichier CSV: %s", input_path)
        df = pd.read_csv(input_path)
        logging.info("CSV chargé: %d lignes, %d colonnes", len(df), len(df.columns))
        
        # Convertir en Parquet
        logging.info("Conversion en Parquet...")
        df.to_parquet(output_path, index=False, compression='snappy')
        
        # Vérifier que le fichier a été créé
        if output_path.exists():
            file_size_mb = output_path.stat().st_size / (1024 * 1024)
            logging.info("Conversion réussie: %s (%.2f MB)", output_path, file_size_mb)
            
            # Résultat JSON
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


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    # Configurer le logging pour que les logs aillent sur stderr
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
            # Appeler la fonction d'analyse
            result = analyze_dataset_file(
                file_path=args.dataset_path,
                sample_rows=args.sample_rows,
                max_rows=args.max_rows if args.max_rows > 0 else None
            )
            # Sérialiser en JSON
            json_output = json.dumps(result, indent=2, default=str)
            # Écrire sur stdout (Java lira ce flux) - SEULEMENT le JSON
            print(json_output)
            # Si un fichier de sortie est demandé, l'écrire aussi
            if args.out:
                out_path = Path(args.out)
                out_path.parent.mkdir(parents=True, exist_ok=True)
                out_path.write_text(json_output, encoding="utf-8")
                # Ce message va sur stderr, pas sur stdout
                print(f"Résultat également sauvegardé dans {out_path}", file=sys.stderr)
        except Exception as e:
            # En cas d'erreur, imprimer un JSON d'erreur sur stdout
            error_result = {"error": str(e)}
            print(json.dumps(error_result))
            # Le stack trace va sur stderr
            logging.exception("Erreur lors de l'analyse")
            sys.exit(1)

    elif args.command == "extract-behavior":
        extract_behavior_command(args)
        
    elif args.command == "convert-to-parquet":
        convert_to_parquet_command(args)

    elif args.command == "validate":
        print("Validation CLI not fully wired yet.", file=sys.stderr)
        print("Use ml_pipeline.training.validate_pipeline directly for now.", file=sys.stderr)


if __name__ == "__main__":
    main()