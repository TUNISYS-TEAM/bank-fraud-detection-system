"""
Command-line interface for the fraud detection ML pipeline.

Usage examples:
    # Train all models defined in config
    python -m ml_pipeline.cli.main train --config config/model_config.json --data exported_parquet/ --out exported_models/

    # Export a specific model to ONNX
    python -m ml_pipeline.cli.main export-onnx --model-type lightgbm --model-path exported_models/lightgbm.joblib --pipeline-path exported_models/pipeline_tree.joblib

    # Run prediction on a single transaction (JSON input)
    python -m ml_pipeline.cli.main predict --model exported_models/lightgbm.joblib --input '{"transaction_id": "tx1", "amount": 100.0, ...}'

    # Generate sample data
    python -m ml_pipeline.cli.main generate-data --output data/samples/ --n-transactions 10000

    # Validate pipeline
    python -m ml_pipeline.cli.main validate --data exported_parquet/
"""

import argparse
import json
import sys
from pathlib import Path

# Add project root to sys.path to allow absolute imports when run as script
project_root = Path(__file__).parent.parent.parent
if str(project_root) not in sys.path:
    sys.path.insert(0, str(project_root))

import pandas as pd

from ml_pipeline.config.model_config import ModelConfig
from ml_pipeline.training.train_models import main as train_main
from ml_pipeline.pipeline.onnx_exporter import ONNXExporter
from ml_pipeline.inference.contract import TransactionInput, PredictionOutput
from ml_pipeline.utils.logging_config import setup_logging
from ml_pipeline.training.validate_pipeline import check_auc_leakage, check_as_of_correctness, quick_signal_test
from scripts.generate_sample_data import generate_sample_data


def setup_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Fraud Detection ML Pipeline CLI",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--log-level", default="INFO", choices=["DEBUG", "INFO", "WARNING", "ERROR"])
    subparsers = parser.add_subparsers(dest="command", required=True, help="Subcommand to run")

    # ---------- train ----------
    train_parser = subparsers.add_parser("train", help="Train all models")
    train_parser.add_argument("--config", default="config/model_config.json", help="Path to model config JSON")
    train_parser.add_argument("--data-dir", default="exported_parquet", help="Directory with preprocessed parquet files")
    train_parser.add_argument("--out", default="exported_models", help="Output directory for trained models")
    train_parser.add_argument("--log", help="Log file path (optional)")
    train_parser.add_argument("--export-onnx", action="store_true", help="Export models to ONNX after training")
    train_parser.add_argument("--onnx-dir", default="exported_onnx", help="Directory for ONNX exports")

    # ---------- export-onnx ----------
    export_parser = subparsers.add_parser("export-onnx", help="Export a trained model to ONNX")
    export_parser.add_argument("--model-type", required=True,
                               choices=["lightgbm", "xgboost", "random_forest", "catboost", "autoencoder"],
                               help="Type of model")
    export_parser.add_argument("--model-path", required=True, help="Path to trained model .joblib file")
    export_parser.add_argument("--pipeline-path", help="Path to fitted pipeline .joblib file (optional)")
    export_parser.add_argument("--output-dir", default="exported_onnx", help="Directory to save ONNX model")
    export_parser.add_argument("--no-verify", action="store_true", help="Skip equivalence verification")

    # ---------- predict ----------
    predict_parser = subparsers.add_parser("predict", help="Run inference on a single transaction or batch file")
    predict_parser.add_argument("--model", required=True, help="Path to trained model .joblib file")
    predict_parser.add_argument("--pipeline", help="Path to fitted pipeline .joblib file (optional)")
    predict_parser.add_argument("--input", required=True,
                                help="JSON string for a single transaction, or path to JSON/CSV file for batch")
    predict_parser.add_argument("--output", help="Output file for batch predictions (CSV)")

    # ---------- generate-data ----------
    gen_parser = subparsers.add_parser("generate-data", help="Generate synthetic transaction data")
    gen_parser.add_argument("--output", default="data/samples/", help="Output directory")
    gen_parser.add_argument("--n-transactions", type=int, default=10000, help="Number of transactions")
    gen_parser.add_argument("--fraud-ratio", type=float, default=0.02, help="Fraction of fraudulent transactions")
    gen_parser.add_argument("--n-clients", type=int, default=1000, help="Number of unique clients")

    # ---------- validate ----------
    validate_parser = subparsers.add_parser("validate", help="Run pipeline validation checks")
    validate_parser.add_argument("--data-dir", default="exported_parquet", help="Directory with preprocessed parquet files")
    validate_parser.add_argument("--target", default="fraud", help="Target column name")

    return parser


def load_model_and_pipeline(model_path: Path, pipeline_path: Path = None):
    """Load model and optional pipeline from disk."""
    import joblib
    model = joblib.load(model_path)
    pipeline = joblib.load(pipeline_path) if pipeline_path and pipeline_path.exists() else None
    return model, pipeline


def predict_single(transaction_dict, model, pipeline=None):
    """Run prediction on a single transaction."""
    # Validate input using Pydantic model
    tx = TransactionInput(**transaction_dict)
    # Convert to DataFrame (the pipeline expects a DataFrame with specific columns)
    df = pd.DataFrame([tx.dict(by_alias=True)])
    # If pipeline provided, transform
    if pipeline:
        features = pipeline.transform(df)
    else:
        # Assume the model expects raw features? In practice you'd have a pipeline.
        # For simplicity, we'll require pipeline for now.
        raise ValueError("Pipeline must be provided for prediction (raw input needs transformation).")
    # Predict
    proba = model.predict_proba(features)[0]
    if hasattr(model, "threshold_"):
        threshold = model.threshold_
    else:
        threshold = 0.5
    is_fraud = proba > threshold
    # Build output
    output = PredictionOutput(
        transaction_id=tx.transaction_id,
        fraud_probability=float(proba),
        fraud_score=float(proba),  # could be raw score if model provides it
        threshold=threshold,
        is_fraud=bool(is_fraud),
        model_name=getattr(model, "name", "unknown"),
        model_version="1.0.0",  # could be read from metadata
    )
    return output.dict()


def batch_predict(input_path: Path, model, pipeline, output_path: Path = None):
    """Run predictions on a CSV/JSON file and optionally save results."""
    if input_path.suffix == ".csv":
        df_input = pd.read_csv(input_path)
    elif input_path.suffix == ".json":
        df_input = pd.read_json(input_path)
    else:
        raise ValueError("Input file must be CSV or JSON")

    # Ensure required columns exist
    required = ["transaction_id", "amount", "timestamp", "category"]  # minimal set
    for col in required:
        if col not in df_input.columns:
            raise ValueError(f"Input file missing required column: {col}")

    # Transform
    if pipeline:
        features = pipeline.transform(df_input)
    else:
        features = df_input  # not recommended, but allowed

    # Predict
    probas = model.predict_proba(features)
    if probas.ndim == 2:
        probas = probas[:, 1]

    thresholds = getattr(model, "threshold_", 0.5)
    is_fraud = probas > thresholds

    # Build output DataFrame
    output_df = pd.DataFrame({
        "transaction_id": df_input["transaction_id"],
        "fraud_probability": probas,
        "threshold": thresholds,
        "is_fraud": is_fraud,
    })

    if output_path:
        output_df.to_csv(output_path, index=False)
        print(f"Batch predictions saved to {output_path}")
    else:
        print(output_df.to_string(index=False))
    return output_df


def main():
    parser = setup_parser()
    args = parser.parse_args()

    # Setup logging
    logger = setup_logging(level=args.log_level)

    if args.command == "train":
        # Delegate to train_models.main, but we need to adapt arguments
        # train_models.main expects argparse args with specific names.
        # We'll create a simple Namespace and call.
        train_args = argparse.Namespace(
            data_dir=args.data_dir,
            out=args.out,
            config=args.config,
            log=args.log,
            level=args.log_level,
            export_onnx=args.export_onnx,
            onnx_dir=args.onnx_dir,
        )
        train_main(train_args)  # train_models.main uses a global logger, but that's okay

    elif args.command == "export-onnx":
        exporter = ONNXExporter(
            model_type=args.model_type,
            model_path=Path(args.model_path),
            pipeline_path=Path(args.pipeline_path) if args.pipeline_path else None,
            output_dir=Path(args.output_dir),
        )
        onnx_path = exporter.export(verify=not args.no_verify)
        print(f"ONNX model exported to: {onnx_path}")

    elif args.command == "predict":
        model_path = Path(args.model)
        pipeline_path = Path(args.pipeline) if args.pipeline else None
        model, pipeline = load_model_and_pipeline(model_path, pipeline_path)

        input_arg = args.input
        # Check if input is a file
        if Path(input_arg).exists():
            # Batch mode
            output_path = Path(args.output) if args.output else None
            batch_predict(Path(input_arg), model, pipeline, output_path)
        else:
            # Single transaction (JSON string)
            try:
                tx_dict = json.loads(input_arg)
            except json.JSONDecodeError:
                print("Error: --input must be a valid JSON string or a path to an existing file.")
                sys.exit(1)
            result = predict_single(tx_dict, model, pipeline)
            print(json.dumps(result, indent=2))

    elif args.command == "generate-data":
        generate_sample_data(
            output_dir=args.output,
            n_transactions=args.n_transactions,
            fraud_ratio=args.fraud_ratio,
            n_clients=args.n_clients,
        )
        print(f"Sample data generated in {args.output}")

    elif args.command == "validate":
        # Load preprocessed data
        data_dir = Path(args.data_dir)
        df_train = pd.read_parquet(data_dir / "train_behavior.parquet")  # or tree, catboost, etc.
        df_val = pd.read_parquet(data_dir / "val_behavior.parquet")
        df_test = pd.read_parquet(data_dir / "test_behavior.parquet")
        y_train = pd.read_parquet(data_dir / "y_train.parquet")["fraud"]
        y_val = pd.read_parquet(data_dir / "y_val.parquet")["fraud"]
        y_test = pd.read_parquet(data_dir / "y_test.parquet")["fraud"]

        print("\n=== AUC Leakage Check ===")
        check_auc_leakage(df_train, df_val, df_test, target_col=args.target)

        print("\n=== AS-OF Correctness Check ===")
        check_as_of_correctness(df_train, "TRAIN")
        check_as_of_correctness(df_val, "VAL")
        check_as_of_correctness(df_test, "TEST")

        print("\n=== Quick Signal Test (Random Forest) ===")
        # Use tree pipeline data (numeric features)
        X_train = pd.read_parquet(data_dir / "train_tree.parquet")
        X_test = pd.read_parquet(data_dir / "test_tree.parquet")
        quick_signal_test(X_train, y_train, X_test, y_test)

    else:
        parser.print_help()


if __name__ == "__main__":
    main()