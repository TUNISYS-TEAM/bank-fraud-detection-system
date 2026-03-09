"""
run_export_onnx.py
==================
Export a trained model to ONNX using the CLI.
"""

import argparse
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description="Export trained model to ONNX.")
    parser.add_argument("--model-type", required=True,
                        choices=["lightgbm", "xgboost", "random_forest", "catboost", "autoencoder"],
                        help="Type of model")
    parser.add_argument("--model-path", required=True, help="Path to trained model .joblib file")
    parser.add_argument("--pipeline-path", help="Path to fitted pipeline .joblib file (optional)")
    parser.add_argument("--output-dir", default="exported_onnx", help="Directory to save ONNX model")
    parser.add_argument("--no-verify", action="store_true", help="Skip equivalence verification")
    args = parser.parse_args()

    cmd = [
        sys.executable, "-m", "ml_pipeline.cli.main",
        "export-onnx",
        "--model-type", args.model_type,
        "--model-path", args.model_path,
        "--output-dir", args.output_dir,
    ]
    if args.pipeline_path:
        cmd.extend(["--pipeline-path", args.pipeline_path])
    if args.no_verify:
        cmd.append("--no-verify")

    print("Running: " + " ".join(cmd))
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()