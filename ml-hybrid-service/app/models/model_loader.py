import os
import joblib
import logging
import io
from minio import Minio
from app.config import settings

logger = logging.getLogger(__name__)

class ModelLoader:
    def __init__(self):
        # Initialisation du client MinIO
        # On retire les préfixes http/https car le client Minio attend juste l'hôte:port
        endpoint = settings.MINIO_ENDPOINT.replace("http://", "").replace("https://", "")
        self.client = Minio(
            endpoint,
            access_key=settings.MINIO_ACCESS_KEY,
            secret_key=settings.MINIO_SECRET_KEY,
            secure=settings.MINIO_ENDPOINT.startswith("https")
        )
        self.bucket = settings.MINIO_BUCKET

    def load_joblib(self, storage_path: str):
        """
        Télécharge un fichier joblib depuis MinIO directement en mémoire (Zéro-Trace sur disque).
        """
        try:
            logger.info(f"Loading {storage_path} from MinIO directly into memory...")
            
            # Récupération de l'objet sous forme de flux (stream)
            response = self.client.get_object(self.bucket, storage_path)
            
            try:
                # Lecture du flux dans un buffer BytesIO
                model_data = io.BytesIO(response.read())
                # Chargement par joblib depuis le buffer mémoire
                return joblib.load(model_data)
            finally:
                response.close()
                response.release_conn()
                
        except Exception as e:
            logger.error(f"Failed to load {storage_path} from memory: {e}")
            raise