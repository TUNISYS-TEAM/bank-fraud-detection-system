import logging
import os
import random

import requests

from app.utils.geo_utils import stable_postal_code_seed

logger = logging.getLogger(__name__)

JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://java-backend:8080")


def _clamp_ratio(value: float) -> float:
    """
    Force une valeur de ratio à rester entre 0 et 1.
    """
    return min(max(float(value), 0.0), 1.0)


def compute_behavior_kpis(postal_code: str, horizon_days: int) -> dict:
    """
    Calcule les KPI comportementaux d'une zone.

    Le service tente d'abord de récupérer les agrégats comportementaux
    depuis le backend Java. En cas d'indisponibilité, il utilise un fallback
    déterministe afin de garder le service fonctionnel.
    """
    horizon_days = max(1, horizon_days)

    try:
        url = f"{JAVA_BACKEND_URL}/api/internal/behavior-stats"
        params = {
            "postalCode": postal_code,
            "windowDays": horizon_days,
        }

        response = requests.get(url, params=params, timeout=10)

        if response.status_code == 200:
            data = response.json()

            behavior_drift_score = _clamp_ratio(data.get("driftScore", 0.5))
            high_risk_customer_ratio = _clamp_ratio(data.get("highRiskRatio", 0.1))

            return {
                "behavior_drift_score": round(behavior_drift_score, 3),
                "high_risk_customer_ratio": round(high_risk_customer_ratio, 3),
            }

        logger.warning(
            "Behavior stats endpoint returned status=%s for postal_code=%s",
            response.status_code,
            postal_code,
        )

    except Exception as exc:
        logger.warning(
            "Unable to fetch behavior stats for postal_code=%s: %s",
            postal_code,
            exc,
        )

    seed = stable_postal_code_seed(postal_code) + horizon_days
    rng = random.Random(seed)

    return {
        "behavior_drift_score": round(rng.uniform(0.1, 0.9), 3),
        "high_risk_customer_ratio": round(rng.uniform(0.05, 0.3), 3),
    }