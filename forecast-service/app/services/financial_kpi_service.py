from predictors.loss_forecaster import LossForecaster

loss_forecaster = LossForecaster()

def compute_financial_kpis(postal_code: str, horizon_days: int) -> dict:
    loss_pred = loss_forecaster.predict(postal_code, horizon_days)
    total_loss = loss_pred["fraud_loss_forecast"]

    # Idéalement, récupérer le nombre de clients actifs depuis Java
    active_customers = 1000
    elc = total_loss / active_customers if active_customers else 0
    cost_fp = total_loss * 0.2
    net_savings = total_loss * 0.8 - cost_fp

    return {
        "fraud_loss_forecast": round(total_loss, 2),
        "expected_loss_per_customer": round(elc, 4),
        "cost_of_false_positives": round(cost_fp, 2),
        "net_savings": round(net_savings, 2)
    }