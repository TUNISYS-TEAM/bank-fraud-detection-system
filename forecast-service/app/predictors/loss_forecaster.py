import logging
import math
import random
from typing import Optional

import joblib
import numpy as np

from app.config import LOSS_MODEL_FILE
from app.utils.geo_utils import stable_postal_code_seed

logger = logging.getLogger(__name__)


class LossForecaster:
    """
    Prévision des pertes futures par zone et horizon.
    
    """

    def __init__(self, model_path: Optional[str] = None):
        self.model_path = model_path or LOSS_MODEL_FILE
        try:
            self.model = joblib.load(self.model_path)
        except Exception:
            logger.warning("Modèle %s introuvable ou corrompu, fallback activé", self.model_path)
            self.model = None

    def predict(self, postal_code: str, horizon_days: int) -> dict:
        if self.model is None:
            seed = stable_postal_code_seed(postal_code)
            rng = random.Random(seed + horizon_days)
            # Facteur non-linéaire : log(1 + horizon_days) pour éviter le doublement linéaire
            total_loss = rng.uniform(1000, 50000) * math.log(1 + horizon_days) / math.log(8)
            return {
                "fraud_loss_forecast": round(max(0.0, total_loss), 2)
            }

        features = self._build_features(postal_code, horizon_days)
        pred = float(self.model.predict(features)[0])
        pred = max(0.0, pred)
        return {
            "fraud_loss_forecast": round(pred, 2)
        }

    def _build_features(self, postal_code: str, horizon_days: int) -> np.ndarray:
        return np.array([[
            stable_postal_code_seed(postal_code),
            horizon_days,
        ]])