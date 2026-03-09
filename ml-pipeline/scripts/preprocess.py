"""
preprocess.py - Convert raw transaction data into pipeline-specific feature sets.
"""

import argparse
import sys
from pathlib import Path

# Add project root to path
project_root = Path(__file__).parent.parent
if str(project_root) not in sys.path:
    sys.path.insert(0, str(project_root))

import pandas as pd
from ml_pipeline.pipeline.data_split import temporal_split, save_split_report
from ml_pipeline.pipeline.fraud_pipeline import (
    create_tree_pipeline,
    create_catboost_pipeline,
    create_dl_pipeline,
    FraudFeaturePipeline,
)


def main():
    parser = argparse.ArgumentParser(description="Preprocess raw transactions into feature sets.")
    parser.add_argument("--input", required=True, help="Path to raw transactions.parquet")
    parser.add_argument("--output", default="exported_parquet", help="Output directory for processed parquets")
    parser.add_argument("--train-ratio", type=float, default=0.7, help="Fraction for training")
    parser.add_argument("--val-ratio", type=float, default=0.15, help="Fraction for validation")
    args = parser.parse_args()

    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Load raw data
    df = pd.read_parquet(args.input)
    print(f"Loaded {len(df)} raw transactions.")

    # Temporal split
    df_train, df_val, df_test = temporal_split(
        df, train_ratio=args.train_ratio, val_ratio=args.val_ratio, time_col="TX_Time"
    )
    save_split_report(df_train, df_val, df_test, output_path=out_dir / "split_report.json")

    # Define pipelines (use the same parameters as in training)
    tree_pipe = create_tree_pipeline()
    catboost_pipe = create_catboost_pipeline()
    dl_pipe = create_dl_pipeline("robust")

    # Wrap and fit each pipeline on TRAIN only
    fp_tree = FraudFeaturePipeline(tree_pipe, name="tree")
    fp_tree.fit(df_train)
    fp_cat = FraudFeaturePipeline(catboost_pipe, name="catboost")
    fp_cat.fit(df_train)
    fp_dl = FraudFeaturePipeline(dl_pipe, name="dl_robust")
    fp_dl.fit(df_train)

    # Transform all splits
    for split_name, df_split in [("train", df_train), ("val", df_val), ("test", df_test)]:
        # Tree pipeline output
        X_tree = fp_tree.transform(df_split)
        pd.DataFrame(X_tree).to_parquet(out_dir / f"{split_name}_tree.parquet", index=False)

        # CatBoost pipeline output
        X_cat = fp_cat.transform(df_split)
        pd.DataFrame(X_cat).to_parquet(out_dir / f"{split_name}_catboost.parquet", index=False)

        # DL pipeline output
        X_dl = fp_dl.transform(df_split)
        pd.DataFrame(X_dl).to_parquet(out_dir / f"{split_name}_dl_robust.parquet", index=False)

        # Labels
        y_split = df_split["fraud"]
        y_split.to_frame().to_parquet(out_dir / f"y_{split_name}.parquet", index=False)

    # Save pipelines for later use (optional)
    fp_tree.save(out_dir / "pipeline_tree.joblib")
    fp_cat.save(out_dir / "pipeline_catboost.joblib")
    fp_dl.save(out_dir / "pipeline_dl_robust.joblib")

    print("Preprocessing complete. Files saved in", out_dir)


if __name__ == "__main__":
    main()