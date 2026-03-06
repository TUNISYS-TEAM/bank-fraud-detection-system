"""
train_models.py  v2
===================
All hyperparameters come from config/model_config.json.
Spring Boot UI writes that file → Python reads it here.
No hardcoded params in this script.

Usage
-----
    python training/train_models.py \
        --data_dir  exported_parquet/ \
        --out       exported_models/ \
        --config    config/model_config.json \
        --log       logs/train_models.log \
        --export-onnx
"""

from __future__ import annotations
import argparse, sys
from pathlib import Path
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent.parent))

from config.model_config      import ModelConfig
from models.ensemble.ensemble import compare_models
from utils.logging_config     import setup_logging
from pipeline.onnx_exporter   import ONNXExporter


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--data_dir", default="exported_parquet")
    p.add_argument("--out",      default="exported_models")
    p.add_argument("--config",   default="config/model_config.json")
    p.add_argument("--log",      default=None)
    p.add_argument("--level",    default="INFO")
    p.add_argument("--export-onnx", action="store_true", 
                   help="Export models to ONNX after training")
    p.add_argument("--onnx-dir", default="exported_onnx",
                   help="Directory to save ONNX models")
    return p.parse_args()


def load_data(data_dir):
    d = Path(data_dir)
    sets = {name: {split: pd.read_parquet(d / f"{split}_{name}.parquet").fillna(0).values
                   for split in ["train","val","test"]}
            for name in ["tree","catboost","dl_robust"]}
    y = {split: pd.read_parquet(d / f"y_{split}.parquet")["fraud"].values
         for split in ["train","val","test"]}
    return sets, y


def export_to_onnx(model_name, model_path, pipeline_path, onnx_dir):
    """Export a trained model to ONNX."""
    try:
        logger.info(f"Exporting {model_name} to ONNX...")
        exporter = ONNXExporter(
            model_type=model_name,
            model_path=model_path,
            pipeline_path=pipeline_path,
            output_dir=onnx_dir,
        )
        onnx_path = exporter.export(verify=True)
        logger.info(f"✅ {model_name} exported to {onnx_path}")
        return onnx_path
    except Exception as e:
        logger.error(f"Failed to export {model_name} to ONNX: {e}")
        return None


def main():
    args   = parse_args()
    logger = setup_logging(level=args.level, log_file=args.log)
    out    = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    
    onnx_dir = Path(args.onnx_dir)
    if args.export_onnx:
        onnx_dir.mkdir(parents=True, exist_ok=True)

    cfg = ModelConfig(args.config)
    logger.info("Config: %s", cfg)

    sets, y = load_data(args.data_dir)
    Xtr, Xval, Xte = (sets["tree"][s]      for s in ["train","val","test"])
    Ctr, Cval, Cte = (sets["catboost"][s]  for s in ["train","val","test"])
    Dtr, Dval, Dte = (sets["dl_robust"][s] for s in ["train","val","test"])

    fitted = {}
    pipeline_map = {}  # Map model names to their pipeline paths

    # ── Tree-space models ──────────────────────────────────────────────
    for name, tr, val, te in [
        ("lightgbm",      Xtr, Xval, Xte),
        ("xgboost",       Xtr, Xval, Xte),
        ("random_forest", Xtr, Xval, Xte),
        ("ecod",          Xtr, Xval, Xte),
        ("copod",         Xtr, Xval, Xte),
    ]:
        if not cfg.is_enabled(name): continue
        print(f"\n{'='*55}\n  {name.upper()}\n{'='*55}")
        m = getattr(cfg, f"build_{name}")()
        m.fit(tr, y["train"], val, y["val"])
        
        model_path = out / f"{name}.joblib"
        m.save(model_path)
        fitted[name] = m
        
        # For tree models, pipeline is "tree"
        pipeline_map[name] = out / "pipeline_tree.joblib"

    # ── CatBoost (own feature space) ──────────────────────────────────
    if cfg.is_enabled("catboost"):
        print(f"\n{'='*55}\n  CATBOOST\n{'='*55}")
        m = cfg.build_catboost()
        m.fit(Ctr, y["train"], Cval, y["val"])
        
        model_path = out / "catboost.joblib"
        m.save(model_path)
        fitted["catboost"] = m
        pipeline_map["catboost"] = out / "pipeline_catboost.joblib"

    # ── DL models ─────────────────────────────────────────────────────
    for name, tr, val, te in [
        ("neural_network", Dtr, Dval, Dte),
        ("autoencoder",    Dtr, Dval, Dte),
    ]:
        if not cfg.is_enabled(name): continue
        print(f"\n{'='*55}\n  {name.upper()}\n{'='*55}")
        m = getattr(cfg, f"build_{name}")()
        m.fit(tr, y["train"], val, y["val"])
        
        model_path = out / f"{name}.joblib"
        m.save(model_path)
        fitted[name] = m
        pipeline_map[name] = out / "pipeline_dl_robust.joblib"

    # ── Ensemble ──────────────────────────────────────────────────────
    print(f"\n{'='*55}\n  ENSEMBLE\n{'='*55}")
    ens = cfg.build_ensemble(fitted)
    ens.fit(Xval, y["val"], Xval, y["val"])
    ens.save(out / "ensemble.joblib")
    fitted["ensemble"] = ens

    # ── ONNX Export ───────────────────────────────────────────────────
    if args.export_onnx:
        print(f"\n{'='*55}\n  EXPORTING TO ONNX\n{'='*55}")
        onnx_results = {}
        
        for name, model in fitted.items():
            if name in ["ecod", "copod", "ensemble"]:
                # Skip unsupervised and ensemble for now
                logger.info(f"Skipping {name} ONNX export (not supported yet)")
                continue
                
            model_path = out / f"{name}.joblib"
            pipeline_path = pipeline_map.get(name)
            
            if pipeline_path and pipeline_path.exists():
                onnx_path = export_to_onnx(name, model_path, pipeline_path, onnx_dir)
                if onnx_path:
                    onnx_results[name] = onnx_path
            else:
                logger.warning(f"No pipeline found for {name}, exporting model only")
                onnx_path = export_to_onnx(name, model_path, None, onnx_dir)
                if onnx_path:
                    onnx_results[name] = onnx_path
        
        print(f"\n✅ Exported {len(onnx_results)} models to ONNX in {onnx_dir}")

    # ── Comparison ────────────────────────────────────────────────────
    tree_models = [fitted[n] for n in
                   ["lightgbm","xgboost","random_forest","ecod","copod","ensemble"]
                   if n in fitted]
    print(f"\n{'='*55}\n  FINAL COMPARISON (TEST)\n{'='*55}")
    comp = compare_models(tree_models, y["test"], Xte)
    print(comp.to_string(index=False))
    comp.to_csv(out / "model_comparison.csv", index=False)

    # Write learned thresholds back to a config sidecar for Spring Boot
    out_cfg = dict(cfg._cfg)
    for name, m in fitted.items():
        if name in out_cfg:
            out_cfg[name]["best_threshold"] = round(m.threshold_, 6)
    
    (out / "model_config_with_thresholds.json").write_text(
        json.dumps(out_cfg, indent=2)
    )
    
    print(f"\nComparison  → {out}/model_comparison.csv")
    print(f"Thresholds  → {out}/model_config_with_thresholds.json")
    if args.export_onnx:
        print(f"ONNX models → {onnx_dir}/")


if __name__ == "__main__":
    main()