"""
Vérification rapide de l'environnement pour le pipeline ML de détection de fraude (utilisant shared_ml).
"""

from __future__ import annotations

import importlib
import sys


def test_imports() -> None:
    """Tente d'importer les principaux modules de shared_ml."""
    modules = [
        "shared_ml.config.model_config",
        "shared_ml.schemas.config_schema",
        "shared_ml.models.base_model",
        "shared_ml.utils.logging_config",
        "shared_ml.utils.postal_codes",
        "shared_ml.evaluation.data_split",
        "shared_ml.evaluation.thresholding",
        "shared_ml.pipeline.fraud_pipeline",
        "shared_ml.inference.inference_pipeline",
        "shared_ml.explanation.xai_engine",
    ]

    print("Test des imports shared_ml...")
    for mod_name in modules:
        try:
            importlib.import_module(mod_name)
            print(f"OK  {mod_name}")
        except Exception as exc:
            print(f"FAIL {mod_name}: {exc}")


def test_postal_resolver() -> None:
    """Test rapide du résolveur de codes postaux."""
    try:
        from shared_ml.utils.postal_codes import get_resolver

        resolver = get_resolver()
        test_zips = ["1000", "2036", "3000", "9999"]

        for postal_code in test_zips:
            gov = resolver.get_gov(postal_code)
            region = resolver.get_region(postal_code)
            print(f"Zip {postal_code}: gov={gov}, region={region}")
    except Exception as exc:
        print(f"Le test du résolveur de codes postaux a échoué : {exc}")


def test_logging() -> None:
    """Teste la configuration de la journalisation (logging)."""
    from shared_ml.utils.logging_config import setup_logging

    logger = setup_logging(level="DEBUG")
    logger.debug("Le message de debug fonctionne")
    logger.info("Le message d'info fonctionne")
    logger.warning("Le message d'avertissement fonctionne")


if __name__ == "__main__":
    print("=" * 60)
    print("Test rapide du pipeline ML (shared_ml)")
    print("=" * 60)

    try:
        test_imports()
        print("\n" + "=" * 60)
        print("Test du résolveur postal...")
        test_postal_resolver()
        print("\n" + "=" * 60)
        print("Test de la journalisation...")
        test_logging()
        print("\n" + "=" * 60)
        print("Test rapide terminé.")
    except Exception as exc:
        print(f"\nLe test rapide a échoué : {exc}")
        sys.exit(1)