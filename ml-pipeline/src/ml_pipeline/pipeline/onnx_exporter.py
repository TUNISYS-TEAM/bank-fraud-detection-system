from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path

import joblib
import numpy as np

from ml_pipeline.pipeline.fraud_pipeline import FraudFeaturePipeline, build_stateless_pipeline
from ml_pipeline.utils.onnx_utils import (
    convert_tree_model_to_onnx,
    save_onnx_metadata,
    verify_onnx_equivalence,
)

logger = logging.getLogger(__name__)


class ONNXExporter:
    """
    Exporte un modèle entraîné et ses métadonnées associées pour le déploiement ONNX.

    Périmètre actuel du projet :
    - modèles tree / boosting / CatBoost : supportés
    - autoencodeur : non exporté vers ONNX ici
    """

    def __init__(
        self,
        model_type: str,
        model_path: Path,
        pipeline_path: Path | None = None,
        output_dir: Path = Path("exported_onnx"),
    ):
        self.model_type = model_type
        self.model_path = Path(model_path)
        self.pipeline_path = Path(pipeline_path) if pipeline_path else None
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

        self.model = self._load_model()
        self.pipeline = self._load_pipeline()
        self.metadata = self._load_metadata()

    def _load_model(self):
        logger.info("Loading model from %s", self.model_path)
        return joblib.load(self.model_path)

    def _load_pipeline(self):
        if self.pipeline_path:
            logger.info("Loading pipeline from %s", self.pipeline_path)
            return FraudFeaturePipeline.load(self.pipeline_path)
        return None

    def _load_metadata(self) -> dict:
        if self.pipeline_path:
            meta_path = self.pipeline_path.with_suffix(".meta.json")
            if meta_path.exists():
                return json.loads(meta_path.read_text(encoding="utf-8"))

        model_meta_path = self.model_path.with_suffix(".meta.json")
        if model_meta_path.exists():
            return json.loads(model_meta_path.read_text(encoding="utf-8"))

        return {}

    def _unwrap_model(self):
        """
        Extrait le modèle interne utile pour récupérer le vrai nombre de features.
        """
        current = self.model
        for attr in ("_model", "_calibrated", "model_", "model", "estimator_", "estimator", "clf", "classifier"):
            if hasattr(current, attr):
                inner = getattr(current, attr)
                if inner is not None and inner is not current:
                    return inner
        return current

    def build_full_pipeline(self):
        """
        Construit le pipeline complet comprenant le pré-traitement aval sans état plus le modèle entraîné.

        Cet aide est conservé pour les futurs flux d'exportation de graphes complets,
        mais la logique d'exportation actuelle convertit toujours le modèle entraîné directement.
        """
        if self.model_type in {"lightgbm", "xgboost", "random_forest"}:
            pipe_type = "tree"
        elif self.model_type == "catboost":
            pipe_type = "catboost"
        elif self.model_type == "autoencoder":
            pipe_type = "dl"
        else:
            pipe_type = "tree"

        scaler_type = self.metadata.get("transformer_params", {}).get("scaler_type", "robust")

        stateless = build_stateless_pipeline(
            model_type=pipe_type,
            scaler_type=scaler_type,
            fitted_params=self.metadata.get("transformer_params", {}),
        )

        from sklearn.pipeline import Pipeline
        return Pipeline(stateless.steps + [("model", self.model)])

    def _resolve_feature_names(self) -> list[str]:
        feature_names = self.metadata.get("feature_names", [])
        if feature_names:
            return feature_names

        transformer_feature_names = self.metadata.get("transformer_params", {}).get("feature_names", [])
        if transformer_feature_names:
            return transformer_feature_names

        if hasattr(self.model, "feature_names_") and getattr(self.model, "feature_names_", None):
            return list(self.model.feature_names_)

        inner_model = self._unwrap_model()

        if hasattr(inner_model, "feature_name_") and getattr(inner_model, "feature_name_", None):
            return list(inner_model.feature_name_)

        if hasattr(inner_model, "feature_names_in_") and getattr(inner_model, "feature_names_in_", None) is not None:
            return [str(x) for x in list(inner_model.feature_names_in_)]

        if hasattr(inner_model, "n_features_in_"):
            n_features = int(inner_model.n_features_in_)
            return [f"feature_{i}" for i in range(n_features)]

        if hasattr(self.model, "n_features_in_"):
            n_features = int(self.model.n_features_in_)
            return [f"feature_{i}" for i in range(n_features)]

        logger.warning("No feature names found in metadata or model. Using fallback names.")
        return [f"feature_{i}" for i in range(20)]

    def export(self, verify: bool = True) -> Path:
        logger.info("Exporting %s model to ONNX", self.model_type)

        feature_names = self._resolve_feature_names()
        n_features = len(feature_names)

        logger.info("Input features (%d): %s", n_features, feature_names[:5])

        if self.model_type in {"lightgbm", "xgboost", "random_forest", "catboost"}:
            onnx_path = convert_tree_model_to_onnx(
                model=self.model,
                model_type=self.model_type,
                output_dir=self.output_dir,
                input_dim=n_features,
                feature_names=feature_names,
            )
        elif self.model_type == "autoencoder":
            raise NotImplementedError(
                "ONNX export for autoencoder is not supported in this project. "
                "Use Keras SavedModel instead."
            )
        else:
            raise ValueError(f"Unsupported model type for ONNX export: {self.model_type}")

        additional_meta = {
            "threshold": getattr(self.model, "threshold_", 0.5),
            "calibrated": getattr(self.model, "calibrate", False),
            "preprocessing": self.metadata.get("transformer_params", {}),
        }
        save_onnx_metadata(onnx_path, self.model_type, feature_names, additional_meta)

        if verify:
            logger.info("Verifying ONNX equivalence...")
            test_data = self._get_test_sample(n_features)
            if test_data is not None:
                verify_onnx_equivalence(
                    original_model=self.model,
                    onnx_path=onnx_path,
                    test_data=test_data,
                )
            else:
                logger.warning("No test data available for verification. Skipping.")

        logger.info("ONNX export complete: %s", onnx_path)
        return onnx_path

    def _get_test_sample(self, n_features: int, n_samples: int = 100):
        """
        Load a small test sample if available, otherwise generate random float32 data.
        """
        test_data_path = Path("data/test_sample.npy")
        if test_data_path.exists():
            return np.load(test_data_path)

        logger.info(
            "Generating random test data with %d samples and %d features",
            n_samples,
            n_features,
        )
        return np.random.randn(n_samples, n_features).astype(np.float32)


def main():
    parser = argparse.ArgumentParser(description="Export trained models to ONNX")
    parser.add_argument(
        "--model-type",
        required=True,
        choices=["lightgbm", "xgboost", "random_forest", "catboost", "autoencoder"],
        help="Type of model to export",
    )
    parser.add_argument(
        "--model-path",
        required=True,
        help="Path to trained model .joblib file",
    )
    parser.add_argument(
        "--pipeline-path",
        help="Path to fitted pipeline .joblib file (optional)",
    )
    parser.add_argument(
        "--output-dir",
        default="exported_onnx",
        help="Directory to save ONNX models",
    )
    parser.add_argument(
        "--no-verify",
        action="store_true",
        help="Skip equivalence verification",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Logging level",
    )

    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
    )

    try:
        exporter = ONNXExporter(
            model_type=args.model_type,
            model_path=args.model_path,
            pipeline_path=args.pipeline_path,
            output_dir=args.output_dir,
        )

        onnx_path = exporter.export(verify=not args.no_verify)
        print(f"\nSuccessfully exported to: {onnx_path}")
        print(f"   Metadata: {onnx_path.with_suffix('.meta.json')}")

    except Exception as e:
        logger.error("Export failed: %s", e)
        raise


if __name__ == "__main__":
    main()