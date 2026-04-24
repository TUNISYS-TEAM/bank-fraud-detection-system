import requests
import os
from typing import Dict, Any

JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://java-backend:8080")

def fetch_training_data(window_days: int = 90) -> Dict[str, Any]:
    """
    Récupère les données d'entraînement depuis le backend Java.
    Retourne un dict conforme à TrainingDataResponse.
    """
    url = f"{JAVA_BACKEND_URL}/api/internal/training-data"
    params = {"windowDays": window_days}
    try:
        resp = requests.get(url, params=params, timeout=120)
        resp.raise_for_status()
        return resp.json()
    except requests.exceptions.RequestException as e:
        raise RuntimeError(f"Impossible de récupérer les données d'entraînement: {e}")