"""
Vérification rapide de l'environnement pour le pipeline ML de détection de fraude.
"""

from __future__ import annotations

import importlib
import sys


def test_imports() -> None:
    """Tente d'importer les principaux modules du projet."""
    modules = [
        "ml_pipeline.config.model_config",
        "ml_pipeline.config.settings",
        "ml_pipeline.config.config_schema",
        "ml_pipeline.models.base_model",
        "ml_pipeline.utils.logging_config",
        "ml_pipeline.utils.onnx_utils",
        "ml_pipeline.utils.postal_codes",
    ]

    optional_modules = [
        "ml_pipeline.pipeline.data_split",
        "ml_pipeline.pipeline.fraud_pipeline",
        "ml_pipeline.training.train_models",
        "ml_pipeline.cli.main",
        "ml_pipeline.inference.contract",
        "ml_pipeline.inference.feature_schema",
        "ml_pipeline.inference.thresholding",
    ]

    print("Test des imports de base...")
    for mod_name in modules:
        importlib.import_module(mod_name)
        print(f"OK  {mod_name}")

    print("\nTest des imports optionnels ou spécifiques au projet...")
    for mod_name in optional_modules:
        try:
            importlib.import_module(mod_name)
            print(f"OK  {mod_name}")
        except Exception as exc:
            print(f"WARN {mod_name}: {exc}")


def test_postal_resolver() -> None:
    """Test rapide du résolveur de codes postaux, si disponible."""
    try:
        from ml_pipeline.utils.postal_codes import get_resolver

        resolver = get_resolver()
        test_zips = ["1000", "2036", "3000", "9999"]

        for postal_code in test_zips:
            gov = resolver.get_gov(postal_code)
            region = resolver.get_region(postal_code)
            print(f"Zip {postal_code}: gov={gov}, region={region}")
    except Exception as exc:
        print(f"Le test du résolveur de codes postaux a été sauté ou a échoué : {exc}")


def test_logging() -> None:
    """Teste la configuration de la journalisation (logging)."""
    from ml_pipeline.utils.logging_config import setup_logging

    logger = setup_logging(level="DEBUG")
    logger.debug("Le message de debug fonctionne")
    logger.info("Le message d'info fonctionne")
    logger.warning("Le message d'avertissement fonctionne")


if __name__ == "__main__":
    print("=" * 60)
    print("Test rapide du pipeline ML de détection de fraude")
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