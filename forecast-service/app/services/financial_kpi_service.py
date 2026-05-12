"""
Service de KPI financiers – Production Ready.
Récupère le nombre réel de clients actifs depuis les transactions.
"""

import logging
from typing import Optional

from app.predictors.loss_forecaster import LossForecaster

logger = logging.getLogger(__name__)

loss_forecaster = LossForecaster()

FALSE_POSITIVE_COST_RATE = 0.20
SAVED_FRAUD_RATE = 0.80


def _get_active_customers(postal_code: Optional[str] = None) -> int:
    """
    Compte les clients distincts réels à partir du cache de transactions.
    Retourne 1000 uniquement si les données sont indisponibles.
    """
    try:
        from app.services.geo_kpi_service import _fetch_all_transactions
        transactions = _fetch_all_transactions()
        if postal_code:
            transactions = [tx for tx in transactions if tx.get("postalCode") == postal_code]
        customers = {tx.get("customerId") for tx in transactions if tx.get("customerId")}
        return len(customers) if customers else 1000
    except Exception:
        return 1000


def compute_financial_kpis(postal_code: Optional[str] = None, horizon_days: int = 7) -> dict:
    horizon_days = max(1, horizon_days)

    loss_prediction = loss_forecaster.predict(
        postal_code=postal_code,
        horizon_days=horizon_days,
    )

    total_loss = loss_prediction.get("fraud_loss_forecast")

    if total_loss is None:
        return {
            "fraud_loss_forecast": None,
            "expected_loss_per_customer": None,
            "cost_of_false_positives": None,
            "net_savings": None,
        }

    total_loss = max(0.0, float(total_loss))
    active_customers = _get_active_customers(postal_code)
    expected_loss_per_customer = total_loss / active_customers if active_customers > 0 else 0.0
    cost_of_false_positives = total_loss * FALSE_POSITIVE_COST_RATE
    saved_fraud_amount = total_loss * SAVED_FRAUD_RATE
    net_savings = saved_fraud_amount - cost_of_false_positives

    return {
        "fraud_loss_forecast": round(total_loss, 2),
        "expected_loss_per_customer": round(expected_loss_per_customer, 4),
        "cost_of_false_positives": round(cost_of_false_positives, 2),
        "net_savings": round(max(0.0, net_savings), 2),
    }