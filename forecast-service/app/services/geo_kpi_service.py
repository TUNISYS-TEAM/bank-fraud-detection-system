from typing import Optional
import requests
import os

from app.schemas.responses import DetailsKPIs, OverviewZoneResponse
from app.services.behavior_kpi_service import compute_behavior_kpis
from app.services.business_kpi_service import compute_business_kpis
from app.services.financial_kpi_service import compute_financial_kpis
from app.services.hotspot_service import compute_risk_geo_kpis
from app.config import POSTAL_CODES
from app.cache.memory_cache import cache

JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://java-backend:8080")

_transactions_cache = None
_transactions_timestamp = 0


def _resolve_risk_level(hotspot_score: Optional[float]) -> Optional[str]:
    if hotspot_score is None:
        return None
    if hotspot_score < 0.30:
        return "LOW"
    if hotspot_score < 0.60:
        return "MEDIUM"
    if hotspot_score < 0.80:
        return "HIGH"
    return "CRITICAL"


def _fetch_all_transactions() -> list:
    global _transactions_cache, _transactions_timestamp
    import time
    now = time.time()
    if _transactions_cache is not None and (now - _transactions_timestamp) < 300:
        return _transactions_cache
    try:
        url = f"{JAVA_BACKEND_URL}/api/internal/training-data"
        resp = requests.get(url, params={"windowDays": 850}, timeout=60)
        if resp.status_code == 200:
            data = resp.json()
            _transactions_cache = data.get("transactions", [])
            _transactions_timestamp = now
            return _transactions_cache
    except Exception:
        pass
    return _transactions_cache or []


def _get_zones_with_fraud() -> dict:
    """
    Retourne un dict {postal_code: {volume, fraud_count}} 
    uniquement pour les zones ayant au moins une fraude historique.
    """
    transactions = _fetch_all_transactions()
    zones = {}
    for tx in transactions:
        pc = tx.get("postalCode", "0000")
        if pc not in zones:
            zones[pc] = {"volume": 0, "fraud_count": 0}
        zones[pc]["volume"] += 1
        if tx.get("isFraud") or tx.get("finalDecision"):
            zones[pc]["fraud_count"] += 1

    # Garder uniquement les zones avec au moins une fraude
    return {pc: stats for pc, stats in zones.items() if stats["fraud_count"] > 0}


def _predict_anomalies_for_zone(postal_code: str, hotspot_score: Optional[float], horizon_days: int) -> Optional[int]:
    """Prédit les anomalies pour une zone UNIQUEMENT si elle a des fraudes historiques."""
    if hotspot_score is None:
        return None

    zones_with_fraud = _get_zones_with_fraud()
    if postal_code not in zones_with_fraud:
        return None  # pas de fraude historique → pas de prédiction

    stats = zones_with_fraud[postal_code]
    estimated_volume = int(stats["volume"] * horizon_days / 850)
    if estimated_volume == 0:
        return None

    predicted = int(hotspot_score * estimated_volume)
    return predicted if predicted > 0 else None


def _predict_global_anomalies(horizon_days: int) -> Optional[int]:
    """
    Somme des anomalies prédites pour toutes les zones ayant des fraudes historiques.
    """
    zones_with_fraud = _get_zones_with_fraud()
    if not zones_with_fraud:
        return None

    total = 0
    for pc, stats in zones_with_fraud.items():
        estimated_volume = int(stats["volume"] * horizon_days / 850)
        if estimated_volume > 0:
            # Utiliser un hotspot moyen de 0.28 (prédiction globale du modèle)
            total += int(0.28 * estimated_volume)

    return total if total > 0 else None


def compute_overview_zone(
    postal_code: Optional[str] = None,
    region: Optional[str] = "Tunisie",
    period: str = "next_7_days",
    horizon_days: int = 7,
) -> OverviewZoneResponse:
    horizon_days = max(1, horizon_days)

    financial_kpis = compute_financial_kpis(postal_code=postal_code, horizon_days=horizon_days)
    geo_kpis = compute_risk_geo_kpis(postal_code=postal_code, horizon_days=horizon_days)
    behavior_kpis = compute_behavior_kpis(postal_code=postal_code, horizon_days=horizon_days)
    business_kpis = compute_business_kpis(postal_code=postal_code, horizon_days=horizon_days)

    hotspot_score = geo_kpis.get("hotspot_score")
    risk_level = _resolve_risk_level(hotspot_score)

    if postal_code:
        label = f"{region} - {postal_code}"
        total_anomalies = _predict_anomalies_for_zone(postal_code, hotspot_score, horizon_days)
    else:
        label = region
        total_anomalies = _predict_global_anomalies(horizon_days)

    return OverviewZoneResponse(
        postal_code=postal_code,
        zone_label=label,
        risk_level=risk_level,
        hotspot_score=hotspot_score,
        total_anomalies=total_anomalies,
        fraud_loss_forecast=financial_kpis.get("fraud_loss_forecast"),
        revenue_at_risk=business_kpis.get("revenue_at_risk"),
        expected_loss_per_customer=financial_kpis.get("expected_loss_per_customer"),
        high_risk_customer_ratio=behavior_kpis.get("high_risk_customer_ratio"),
        net_savings=financial_kpis.get("net_savings"),
        details=DetailsKPIs(
            cost_of_false_positives=financial_kpis.get("cost_of_false_positives"),
            cross_border_anomaly_rate=geo_kpis.get("cross_border_anomaly_rate"),
            behavior_drift_score=behavior_kpis.get("behavior_drift_score"),
            predicted_ca_impact=business_kpis.get("predicted_ca_impact"),
            transaction_approval_rate=business_kpis.get("approval_rate"),
            customer_friction_score=business_kpis.get("friction_score"),
        ),
    )


def compute_all_kpis_for_region(postal_code: str, horizon_days: int) -> OverviewZoneResponse:
    return compute_overview_zone(
        postal_code=postal_code,
        region="Zone",
        period=f"next_{horizon_days}_days",
        horizon_days=horizon_days,
    )