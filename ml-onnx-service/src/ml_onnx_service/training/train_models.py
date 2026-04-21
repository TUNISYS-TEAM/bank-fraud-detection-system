from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path

import pandas as pd

from ml_onnx_service.models.model_factory import ModelFactory
from ml_onnx_service.models.ensemble.ensemble import compare_models
from ml_onnx_service.pipeline.onnx_exporter import ONNXExporter
from ml_onnx_service.config import ModelConfig
from shared_ml.utils.logging_config import setup_logging

logger = logging.getLogger(__name__)


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data_dir", default="exported_parquet")
    parser.add_argument("--out", default="exported_models")
    parser.add_argument("--config", default="config/model_config.json")
    parser.add_argument("--log", default=None)
    parser.add_argument("--level", default="INFO")
    parser.add_argument(
        "--export-onnx",
        action="store_true",
        help="Export supported trained models to ONNX after training",
    )
    parser.add_argument(
        "--onnx-dir",
        default="exported_onnx",
        help="Directory to save ONNX models",
    )
    return parser.parse_args()


def load_data(data_dir):
    data_dir = Path(data_dir)
    if not data_dir.exists():
        raise FileNotFoundError(f"Data directory not found: {data_dir}")

    feature_sets = {
        name: {
            split: pd.read_parquet(data_dir / f"{split}_{name}.parquet").fillna(0).values
            for split in ["train", "val", "test"]
        }
        for name in ["tree", "catboost", "dl_robust"]
    }

    targets = {
        split: pd.read_parquet(data_dir / f"y_{split}.parquet")["fraud"].values
        for split in ["train", "val", "test"]
    }

    return feature_sets, targets


def export_to_onnx(model_name, model_path, pipeline_path, onnx_dir):
    """Exporte un modèle entraîné au format ONNX."""
    try:
        logger.info("Exporting %s to ONNX...", model_name)
        exporter = ONNXExporter(
            model_type=model_name,
            model_path=model_path,
            pipeline_path=pipeline_path,
            output_dir=onnx_dir,
        )
        onnx_path = exporter.export(verify=True)
        logger.info("Exported %s to %s", model_name, onnx_path)
        return onnx_path
    except Exception as exc:
        logger.error("Failed to export %s to ONNX: %s", model_name, exc)
        return None


def main():
    args = parse_args()
    setup_logging(level=args.level, log_file=args.log)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    onnx_dir = Path(args.onnx_dir)
    if args.export_onnx:
        onnx_dir.mkdir(parents=True, exist_ok=True)

    cfg = ModelConfig(args.config)
    logger.info("Loaded model config from %s", args.config)

    sets, y = load_data(args.data_dir)
    Xtr, Xval, Xte = (sets["tree"][s] for s in ["train", "val", "test"])
    Ctr, Cval, Cte = (sets["catboost"][s] for s in ["train", "val", "test"])
    Dtr, Dval, Dte = (sets["dl_robust"][s] for s in ["train", "val", "test"])

    fitted = {}
    pipeline_map = {}

    # Tree-space models (Supervised only)
    for name, tr, val, te in [
        ("lightgbm", Xtr, Xval, Xte),
        ("xgboost", Xtr, Xval, Xte),
        ("random_forest", Xtr, Xval, Xte),
    ]:
        if not cfg.is_enabled(name):
            continue

        print(f"\n{'=' * 55}\n  {name.upper()}\n{'=' * 55}")
        model = ModelFactory.build_managed_model(name, cfg)
        model.fit(tr, y["train"], val, y["val"])

        model_path = out / f"{name}.joblib"
        model.save(model_path)

        fitted[name] = model
        pipeline_map[name] = out / "pipeline_tree.joblib"

    # CatBoost
    if cfg.is_enabled("catboost"):
        print(f"\n{'=' * 55}\n  CATBOOST\n{'=' * 55}")
        model = ModelFactory.build_catboost(cfg)
        model.fit(Ctr, y["train"], Cval, y["val"])

        model_path = out / "catboost.joblib"
        model.save(model_path)

        fitted["catboost"] = model
        pipeline_map["catboost"] = out / "pipeline_catboost.joblib"


    # Comparison of simple models (no ensemble here as they were moved to hybrid)

    # ONNX export
    if args.export_onnx:
        print(f"\n{'=' * 55}\n  EXPORTING TO ONNX\n{'=' * 55}")
        onnx_results = {}

        for name in fitted:
            if name in {"ecod", "copod", "ensemble", "autoencoder"}:
                logger.info("Skipping %s ONNX export (not supported yet)", name)
                continue

            model_path = out / f"{name}.joblib"
            pipeline_path = pipeline_map.get(name)

            if pipeline_path and pipeline_path.exists():
                onnx_path = export_to_onnx(name, model_path, pipeline_path, onnx_dir)
            else:
                logger.warning("No pipeline found for %s, exporting model only", name)
                onnx_path = export_to_onnx(name, model_path, None, onnx_dir)

            if onnx_path:
                onnx_results[name] = onnx_path

        print(f"\nExported {len(onnx_results)} models to ONNX in {onnx_dir}")

    tree_models = list(fitted.values())

    print(f"\n{'=' * 55}\n  FINAL COMPARISON (TEST)\n{'=' * 55}")
    comparison = compare_models(tree_models, y["test"], Xte)
    print(comparison.to_string(index=False))
    comparison.to_csv(out / "model_comparison.csv", index=False)

    # Save learned thresholds for backend reuse
    out_cfg = cfg._cfg.model_dump(by_alias=True)
    for name, model in fitted.items():
        if name in out_cfg:
            out_cfg[name]["best_threshold"] = round(model.threshold_, 6)

    (out / "model_config_with_thresholds.json").write_text(
        json.dumps(out_cfg, indent=2),
        encoding="utf-8",
    )

    print(f"\nComparison  → {out / 'model_comparison.csv'}")
    print(f"Thresholds  → {out / 'model_config_with_thresholds.json'}")
    if args.export_onnx:
        print(f"ONNX models → {onnx_dir}/")


if __name__ == "__main__":
    main()