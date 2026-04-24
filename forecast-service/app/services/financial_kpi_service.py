from predictors.loss_forecaster import LossForecaster

# Instanciez le prédicteur une fois (sera chargé avec le modèle réel)
loss_forecaster = LossForecaster()

def compute_financial_kpis(postal_code: str, horizon_days: int) -> dict:
    # Obtenir la perte prévue
    loss_pred = loss_forecaster.predict(postal_code, horizon_days)
    total_loss = loss_pred["fraud_loss_forecast"]

    # Calculer ELC : exemple 1000 clients par zone (à remplacer par une donnée réelle)
    active_customers = 1000
    elc = total_loss / active_customers if active_customers else 0

    # Coût des faux positifs : supposons 20 % de la perte prévue
    cost_fp = total_loss * 0.2

    # Économies nettes : perte évitée (par exemple 80% de la perte prévue) moins coût d'intervention
    net_savings = total_loss * 0.8 - cost_fp

    return {
        "fraud_loss_forecast": round(total_loss, 2),
        "expected_loss_per_customer": round(elc, 4),
        "cost_of_false_positives": round(cost_fp, 2),
        "net_savings": round(net_savings, 2)
    }