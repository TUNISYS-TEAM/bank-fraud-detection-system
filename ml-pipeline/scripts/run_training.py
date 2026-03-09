"""
run_training.py
===============
Simple wrapper to run the full training pipeline with sensible defaults.
Calls the CLI `train` subcommand.
"""

import argparse
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Run model training.")
    parser.add_argument("--config", default="config/model_config.json", help="Path to model config JSON")
    parser.add_argument("--data-dir", default="exported_parquet", help="Directory with preprocessed parquet files")
    parser.add_argument("--out", default="exported_models", help="Output directory for trained models")
    parser.add_argument("--export-onnx", action="store_true", help="Export models to ONNX after training")
    parser.add_argument("--onnx-dir", default="exported_onnx", help="Directory for ONNX exports")
    parser.add_argument("--log", help="Log file path (optional)")
    parser.add_argument("--log-level", default="INFO", help="Logging level")
    args = parser.parse_args()

    # Build CLI command
    cmd = [
        sys.executable, "-m", "ml_pipeline.cli.main",
        "train",
        "--config", args.config,
        "--data-dir", args.data_dir,
        "--out", args.out,
        "--log-level", args.log_level,
    ]
    if args.log:
        cmd.extend(["--log", args.log])
    if args.export_onnx:
        cmd.append("--export-onnx")
        cmd.extend(["--onnx-dir", args.onnx_dir])

    print("Running: " + " ".join(cmd))
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()