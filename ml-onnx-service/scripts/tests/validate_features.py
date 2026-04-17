"""
Valide que les caractéristiques transformées par le pipeline correspondent au schéma d'inférence attendu.
"""

from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

import pandas as pd

from shared_ml.schemas.feature_schema import FeatureSchema
from shared_ml.pipeline.fraud_pipeline import FraudFeaturePipeline
from shared_ml.utils.logging_config import setup_logging

PROJECT_ROOT = Path(__file__).resolve().parents[2]  
DEFAULT_RAW_DATA = PROJECT_ROOT / "data" / "samples" / "transactions.parquet"


def load_sample_data(raw_path: Path, n_samples: int = 100) -> pd.DataFrame:
    """Charge les transactions brutes, ou génère un échantillon temporaire s'il est manquant."""
    if raw_path.exists():
        df = pd.read_parquet(raw_path)
        return df.head(n_samples)

    from scripts.generate_sample_data import generate_sample_data

    with tempfile.TemporaryDirectory() as tmpdir:
        generate_sample_data(
            output_dir=tmpdir,
            n_transactions=n_samples,
            fraud_ratio=0.02,
        )
        df = pd.read_parquet(Path(tmpdir) / "transactions.parquet")

    return df


def validate_features(
    pipeline_path: Path,
    raw_data_path: Path,
    n_samples: int = 100,
) -> bool:
    """Passe les données brutes à travers un pipeline ajusté et valide par rapport au schéma des caractéristiques."""
    logger = setup_logging(level="INFO")

    if not pipeline_path.exists():
        raise FileNotFoundError(f"Pipeline non trouvé : {pipeline_path}")

    pipeline = FraudFeaturePipeline.load(pipeline_path)
    logger.info(f"Pipeline chargé depuis {pipeline_path}")

    df_raw = load_sample_data(raw_data_path, n_samples)
    logger.info(f"Chargement de {len(df_raw)} transactions brutes")

    try:
        X_transformed = pipeline.transform(df_raw)
    except Exception as exc:
        logger.error(f"Échec de la transformation du pipeline : {exc}")
        return False

    expected_features = FeatureSchema.get_feature_names()
    n_expected = len(expected_features)

    if isinstance(X_transformed, pd.DataFrame):
        features_out = list(X_transformed.columns)

        missing = set(expected_features) - set(features_out)
        extra = set(features_out) - set(expected_features)

        if missing:
            logger.error(f"Caractéristiques manquantes : {sorted(missing)[:10]}")
        if extra:
            logger.warning(f"Caractéristiques en trop : {sorted(extra)[:10]}")

        if missing:
            return False

        logger.info("Toutes les caractéristiques attendues sont présentes.")
        return True

    if hasattr(X_transformed, "shape"):
        if X_transformed.shape[1] != n_expected:
            logger.error(
                f"Écart dans le nombre de caractéristiques : obtenu {X_transformed.shape[1]}, attendu {n_expected}"
            )
            return False

        logger.info(f"Nombre de caractéristiques OK : {X_transformed.shape[1]} caractéristiques.")
        return True

    logger.error("Type de sortie transformée non supporté.")
    return False


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Valide la sortie du pipeline par rapport au schéma des caractéristiques attendu."
    )
    parser.add_argument(
        "--pipeline",
        required=True,
        help="Chemin vers le fichier .joblib du pipeline ajusté",
    )
    parser.add_argument(
        "--raw-data",
        default=str(DEFAULT_RAW_DATA),
        help="Chemin vers le fichier parquet des transactions brutes",
    )
    parser.add_argument(
        "--n-samples",
        type=int,
        default=100,
        help="Nombre de transactions à tester",
    )
    args = parser.parse_args()

    success = validate_features(
        pipeline_path=Path(args.pipeline),
        raw_data_path=Path(args.raw_data),
        n_samples=args.n_samples,
    )
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()