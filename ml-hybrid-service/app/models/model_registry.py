import logging
import sys
from typing import Dict, Any
from app.models.model_loader import ModelLoader
from app.models import bundles

# --- LOGIQUE D'ALIASING DES MODULES ---
# Permet à joblib de charger les classes (Bundle, modèles d'anomalies) 
# même si elles ont été sérialisées dans un autre contexte (script training ou package ml_pipeline).
sys.modules['train_hybrid_ensemble'] = bundles
sys.modules['ml_pipeline.models.unsupervised.anomaly_models'] = bundles
sys.modules['ml_pipeline.models.base_model'] = bundles # Pour RankNormalizer etc.
# --------------------------------------

logger = logging.getLogger(__name__)

class ModelRegistry:
    def __init__(self):
        self.loader = ModelLoader()
        self.models: Dict[str, Any] = {}

    def get_model(self, model_uuid: str, metadata: Dict[str, Any] = None) -> Any:
        """Récupère un modèle depuis le cache ou le charge depuis MinIO."""
        if model_uuid not in self.models:
            if not metadata:
                raise ValueError(f"Le modèle {model_uuid} n'est pas dans le registre et aucune métadonnée n'est fournie.")
            
            logger.info(f"Chargement des artefacts pour le modèle {model_uuid}...")
            self.models[model_uuid] = self._load_model_artifact(metadata)
            
        return self.models[model_uuid]

    def _load_model_artifact(self, metadata: Dict[str, Any]) -> Dict[str, Any]:
        """Charge le modèle (joblib) et optionnellement son pipeline de pré-traitement."""
        model_path = metadata.get("model_path")
        pipeline_path = metadata.get("pipeline_path")

        if not model_path:
            raise ValueError("Le champ 'model_path' est manquant dans les métadonnées du modèle.")
            
        logger.info(f"Chargement du modèle joblib: {model_path}")
        model = self.loader.load_joblib(model_path)
        
        preprocessor = None
        if pipeline_path:
            logger.info(f"Chargement du pipeline de pré-traitement: {pipeline_path}")
            preprocessor = self.loader.load_joblib(pipeline_path)

        return {
            "model": model,
            "preprocessor": preprocessor
        }

registry = ModelRegistry()