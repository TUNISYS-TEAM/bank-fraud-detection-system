import logging
from typing import Optional

import joblib
import numpy as np

from app.config import LOSS_MODEL_FILE
from app.utils.geo_utils import stable_postal_code_seed

logger = logging.getLogger(__name__)


class LossForecaster:
    """
    Prévision des pertes futures par zone et horizon.
    Retourne None si le modèle n'est pas disponible.
    """

    def __init__(self, model_path: Optional[str] = None):
        self.model_path = model_path or LOSS_MODEL_FILE
        self.model = None
        self._load_model()

    def _load_model(self):
        try:
            self.model = joblib.load(self.model_path)
        except Exception:
            logger.warning("Modèle %s introuvable, KPI indisponible", self.model_path)
            self.model = None

    def reload(self):
        """Recharge le modèle depuis le disque (après entraînement)."""
        self._load_model()

    def predict(self, postal_code: Optional[str], horizon_days: int) -> dict:
        if self.model is None:
            return {"fraud_loss_forecast": None}

        features = self._build_features(postal_code, horizon_days)
        pred = float(self.model.predict(features)[0])
        return {
            "fraud_loss_forecast": round(max(0.0, pred), 2)
        }

    def _build_features(self, postal_code: Optional[str], horizon_days: int) -> np.ndarray:
        seed = hash(postal_code or "GLOBAL") % 10000
        return np.array([[
            seed,     # code postal encodé
            0,        # tx_count_30d (non disponible en prédiction)
            0,        # fraud_count_30d
            0.0,      # total_amount_30d
            0.0,      # fraud_amount_30d
            0.0,      # avg_fraud_score_30d
            0.0,      # amount
            0.0,      # fraud_score
            float(horizon_days),
        ]], dtype=np.float32)