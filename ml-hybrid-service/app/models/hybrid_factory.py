from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from shared_ml.config.model_config import ModelConfig

from app.models.unsupervised.anomaly_models import (
    AnomalyAutoencoder,
    COPODFraudModel,
    ECODFraudModel,
)
from app.models.bundles import HybridEnsembleBundle

logger = logging.getLogger(__name__)


class HybridFactory:
    """
    Factory pour instancier les modèles hybrides et non-supervisés
    spécifiques au service ml-hybrid-service.
    """

    @staticmethod
    def build_autoencoder(cfg: ModelConfig) -> AnomalyAutoencoder:
        c = cfg.get("autoencoder")
        g = cfg.get("global_config")
        return AnomalyAutoencoder(
            encoding_dim=c.encoding_dim,
            epochs=c.epochs,
            batch_size=c.batch_size,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    @staticmethod
    def build_ecod(cfg: ModelConfig) -> ECODFraudModel:
        c = cfg.get("ecod")
        g = cfg.get("global_config")
        return ECODFraudModel(
            contamination=c.contamination,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    @staticmethod
    def build_copod(cfg: ModelConfig) -> COPODFraudModel:
        c = cfg.get("copod")
        g = cfg.get("global_config")
        return COPODFraudModel(
            contamination=c.contamination,
            threshold_metric=g.threshold_metric,
            beta=g.threshold_beta,
        )

    @classmethod
    def build_hybrid_ensemble(cls, cfg: ModelConfig, **kwargs) -> HybridEnsembleBundle:
        """
        Construit un bundle de modèle hybride. 
        Note: Cette méthode prépare l'objet, mais les sous-modèles 
        doivent généralement être chargés ou entraînés séparément.
        """
        # Logique simplifiée pour l'instant
        return HybridEnsembleBundle(**kwargs)

    @classmethod
    def build_managed_model(cls, model_name: str, cfg: ModelConfig, **kwargs) -> Any:
        """Construit un modèle hybride ou non-supervisé par son nom."""
        builders = {
            "autoencoder": cls.build_autoencoder,
            "ecod": cls.build_ecod,
            "copod": cls.build_copod,
        }
        
        if model_name not in builders:
            raise ValueError(f"Unknown hybrid/unsupervised model type: {model_name}")
            
        return builders[model_name](cfg, **kwargs)