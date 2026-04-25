"""
Service de KPI comportementaux – Production Ready.
Calcule le drift et le ratio de clients à haut risque localement
à partir des transactions brutes, avec fallback vers le backend Java.
"""

import logging
import os
from typing import Optional
from collections import defaultdict

import requests

from app.utils.geo_utils import stable_postal_code_seed

logger = logging.getLogger(__name__)

JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://java-backend:8080")
STATS_WINDOW_DAYS = 850


def _compute_local_behavior(postal_code: Optional[str], horizon_days: int) -> dict:
    """
    Calcule les KPIs comportementaux à partir des transactions brutes
    récupérées via /api/internal/training-data.
    """
    try:
        url = f"{JAVA_BACKEND_URL}/api/internal/training-data"
        resp = requests.get(url, params={"windowDays": STATS_WINDOW_DAYS}, timeout=30)
        if resp.status_code != 200:
            return {"behavior_drift_score": None, "high_risk_customer_ratio": None}

        data = resp.json()
        transactions = data.get("transactions", [])

        if not transactions:
            return {"behavior_drift_score": None, "high_risk_customer_ratio": None}

        # Filtrer par code postal si spécifié
        if postal_code:
            transactions = [tx for tx in transactions if tx.get("postalCode") == postal_code]

        if not transactions:
            return {"behavior_drift_score": None, "high_risk_customer_ratio": None}

        # Calcul du drift : écart-type des fraudScores / moyenne des fraudScores
        fraud_scores = [float(tx["fraudScore"]) for tx in transactions if tx.get("fraudScore") is not None]
        total_customers = len(set(tx.get("customerId") for tx in transactions if tx.get("customerId")))
        high_risk_customers = len(set(
            tx.get("customerId") for tx in transactions
            if tx.get("fraudScore") is not None and float(tx["fraudScore"]) >= 0.7
        ))

        if fraud_scores:
            avg_score = sum(fraud_scores) / len(fraud_scores)
            std_score = (sum((s - avg_score) ** 2 for s in fraud_scores) / len(fraud_scores)) ** 0.5
            drift = min(std_score / avg_score if avg_score > 0 else 0.0, 1.0)
        else:
            drift = 0.0

        high_risk_ratio = high_risk_customers / total_customers if total_customers > 0 else 0.0

        return {
            "behavior_drift_score": round(drift, 3),
            "high_risk_customer_ratio": round(high_risk_ratio, 3),
        }

    except Exception as exc:
        logger.warning("Local behavior computation failed for postal_code=%s: %s", postal_code, exc)
        return {"behavior_drift_score": None, "high_risk_customer_ratio": None}


def compute_behavior_kpis(postal_code: Optional[str] = None, horizon_days: int = 7) -> dict:
    """
    Calcule les KPI comportementaux d'une zone.
    Essaie d'abord le backend Java, puis un calcul local à partir des transactions.
    """
    # Essayer d'abord l'endpoint behavior-stats (plus rapide)
    try:
        url = f"{JAVA_BACKEND_URL}/api/internal/behavior-stats"
        params = {"windowDays": STATS_WINDOW_DAYS}
        if postal_code:
            params["postalCode"] = postal_code

        response = requests.get(url, params=params, timeout=10)

        if response.status_code == 200:
            data = response.json()
            drift_score = data.get("driftScore")
            high_risk_ratio = data.get("highRiskRatio")

            if drift_score is not None and float(drift_score) > 0:
                return {
                    "behavior_drift_score": round(float(drift_score), 3),
                    "high_risk_customer_ratio": round(float(high_risk_ratio), 3) if high_risk_ratio is not None else None,
                }

    except Exception:
        pass

    # Fallback : calcul local à partir des transactions
    return _compute_local_behavior(postal_code, horizon_days)