from app.predictors.loss_forecaster import LossForecaster

loss_forecaster = LossForecaster()

DEFAULT_ACTIVE_CUSTOMERS = 1000
FALSE_POSITIVE_COST_RATE = 0.20
SAVED_FRAUD_RATE = 0.80


def compute_financial_kpis(postal_code: str, horizon_days: int) -> dict:
    """
    Calcule les KPI financiers prédictifs d'une zone.

    En V1, certains indicateurs sont estimés à partir de la perte prévue :
    - expected_loss_per_customer ;
    - cost_of_false_positives ;
    - net_savings.

    Ces hypothèses pourront être remplacées ensuite par des agrégats réels
    fournis par le backend.
    """
    horizon_days = max(1, horizon_days)

    loss_prediction = loss_forecaster.predict(
        postal_code=postal_code,
        horizon_days=horizon_days,
    )

    total_loss = max(0.0, float(loss_prediction["fraud_loss_forecast"]))

    active_customers = DEFAULT_ACTIVE_CUSTOMERS

    expected_loss_per_customer = (
        total_loss / active_customers if active_customers > 0 else 0.0
    )

    cost_of_false_positives = total_loss * FALSE_POSITIVE_COST_RATE
    saved_fraud_amount = total_loss * SAVED_FRAUD_RATE
    net_savings = saved_fraud_amount - cost_of_false_positives

    return {
        "fraud_loss_forecast": round(total_loss, 2),
        "expected_loss_per_customer": round(expected_loss_per_customer, 4),
        "cost_of_false_positives": round(cost_of_false_positives, 2),
        "net_savings": round(max(0.0, net_savings), 2),
    }