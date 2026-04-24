import logging
import sys
from typing import Dict, Any
from app.models.model_loader import ModelLoader
from app.models import bundles

from app.models.unsupervised import anomaly_models
from shared_ml.models import base_model

# --- LOGIQUE D'ALIASING DES MODULES ---
# Permet à joblib de charger les anciens modèles même après le refactoring
sys.modules['train_hybrid_ensemble'] = bundles
sys.modules['ml_pipeline.models.unsupervised.anomaly_models'] = anomaly_models
sys.modules['ml_pipeline.models.base_model'] = base_model
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
                raise ValueError(
                    f"Le modèle {model_uuid} n'est pas dans le registre et aucune métadonnée n'est fournie."
                )

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

        # --- FIX SPECIFIQUE AUTOENCODER (Multi-fichiers sur MinIO) ---
        class_name = type(model).__name__
        if class_name == "AnomalyAutoencoder" and getattr(model, "_model", None) is None:
            try:
                keras_path = model_path.replace(".joblib", "_keras.keras")
                logger.info(
                    f"Tentative de restauration manuelle du modèle Keras depuis MinIO: "
                    f"{keras_path} (Bucket: {self.loader.bucket})"
                )

                try:
                    response = self.loader.client.get_object(self.loader.bucket, keras_path)
                except Exception as me:
                    logger.error(f"Fichier Keras introuvable sur MinIO à {keras_path}: {me}")
                    alt_path = model_path.replace(".joblib", ".keras")
                    logger.info(f"Tentative alternative avec: {alt_path}")
                    response = self.loader.client.get_object(self.loader.bucket, alt_path)

                import tempfile
                from tensorflow import keras

                with tempfile.NamedTemporaryFile(suffix=".keras", delete=False) as tmp:
                    tmp.write(response.read())
                    tmp_path = tmp.name
                try:
                    model._model = keras.models.load_model(tmp_path)
                    logger.info("Autoencoder Keras restauré avec succès.")
                finally:
                    import os
                    if os.path.exists(tmp_path):
                        os.unlink(tmp_path)
                response.close()
                response.release_conn()
            except Exception as ke:
                logger.error(
                    f"ECHEC CRITIQUE de restauration Keras pour l'Autoencoder: {ke}",
                    exc_info=True
                )
        # -------------------------------------------------------------

        preprocessor = None
        if pipeline_path:
            logger.info(f"Chargement du pipeline de pré-traitement: {pipeline_path}")
            preprocessor = self.loader.load_joblib(pipeline_path)

            try:
                onehot = preprocessor.named_steps.get("onehot")
                if onehot is not None:
                    if "category" in getattr(onehot, "_encoders", {}):
                        logger.warning(
                            "OneHot learned category values: %s",
                            list(onehot._encoders["category"].categories_[0])
                        )
                    else:
                        logger.warning("OneHot encoder has no fitted 'category' encoder")

                    if "Channel" in getattr(onehot, "_encoders", {}):
                        logger.warning(
                            "OneHot learned Channel values: %s",
                            list(onehot._encoders["Channel"].categories_[0])
                        )
                    else:
                        logger.warning("OneHot encoder has no fitted 'Channel' encoder")
                else:
                    logger.warning("Preprocessor has no 'onehot' step")
            except Exception as e:
                logger.warning("Failed to inspect onehot learned categories: %s", e)

        threshold = metadata.get("threshold")
        if threshold is None:
            threshold = getattr(model, "threshold_", None)

        logger.warning(
            "Loaded artifact bundle | model_uuid=%s | metadata.threshold=%s | model.threshold_=%s",
            metadata.get("model_uuid"),
            metadata.get("threshold"),
            getattr(model, "threshold_", None),
        )

        return {
            "model": model,
            "preprocessor": preprocessor,
            "threshold": threshold,
            "model_uuid": metadata.get("model_uuid"),
        }

registry = ModelRegistry()