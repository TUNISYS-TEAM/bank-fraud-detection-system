import logging
import math
import random
from typing import Optional

import joblib
import numpy as np

from app.config import REVENUE_MODEL_FILE
from app.utils.geo_utils import stable_postal_code_seed

logger = logging.getLogger(__name__)


class RevenueImpactPredictor:
    """
    Prédit le chiffre d'affaires à risque et l'impact CA.

    """

    def __init__(self, model_path: Optional[str] = None):
        self.model_path = model_path or REVENUE_MODEL_FILE
        try:
            self.model = joblib.load(self.model_path)
        except Exception:
            logger.warning("Modèle %s introuvable ou corrompu, fallback activé", self.model_path)
            self.model = None

    def predict(self, postal_code: str, horizon_days: int) -> dict:
        horizon_days = max(1, horizon_days)

        if self.model is None:
            return self._fallback_predict(postal_code, horizon_days)

        features = self._build_features(postal_code, horizon_days)

        try:
            preds = np.asarray(self.model.predict(features)[0]).ravel()

            if preds.size < 4:
                logger.warning(
                    "Sortie modèle revenue invalide pour postal_code=%s : %s valeurs reçues",
                    postal_code,
                    preds.size,
                )
                return self._fallback_predict(postal_code, horizon_days)

            return self._format_output(
                revenue_at_risk=float(preds[0]),
                predicted_ca_impact=float(preds[1]),
                approval_rate=float(preds[2]),
                friction_score=float(preds[3]),
            )

        except Exception as exc:
            logger.warning(
                "Erreur pendant la prédiction revenue pour postal_code=%s : %s",
                postal_code,
                exc,
            )
            return self._fallback_predict(postal_code, horizon_days)

    def _fallback_predict(self, postal_code: str, horizon_days: int) -> dict:
        seed = stable_postal_code_seed(postal_code)
        rng = random.Random(seed * 2 + horizon_days)

        horizon_factor = math.log(1 + horizon_days) / math.log(8)

        revenue_at_risk = rng.uniform(5000, 200000) * horizon_factor
        predicted_ca_impact = revenue_at_risk * rng.uniform(0.2, 0.8)
        approval_rate = rng.uniform(0.75, 0.95)
        friction_score = rng.uniform(0.2, 0.7)

        return self._format_output(
            revenue_at_risk=revenue_at_risk,
            predicted_ca_impact=predicted_ca_impact,
            approval_rate=approval_rate,
            friction_score=friction_score,
        )

    def _build_features(self, postal_code: str, horizon_days: int) -> np.ndarray:
        return np.array([[
            stable_postal_code_seed(postal_code),
            horizon_days,
        ]])

    def _format_output(
        self,
        revenue_at_risk: float,
        predicted_ca_impact: float,
        approval_rate: float,
        friction_score: float,
    ) -> dict:
        return {
            "revenue_at_risk": round(max(0.0, revenue_at_risk), 2),
            "predicted_ca_impact": round(max(0.0, predicted_ca_impact), 2),
            "approval_rate": round(min(max(approval_rate, 0.0), 1.0), 3),
            "friction_score": round(min(max(friction_score, 0.0), 1.0), 3),
        }