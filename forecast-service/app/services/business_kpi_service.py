from typing import Optional
from app.predictors.revenue_impact_predictor import RevenueImpactPredictor

revenue_predictor = RevenueImpactPredictor()

def compute_business_kpis(postal_code: Optional[str] = None, horizon_days: int = 7) -> dict:
    preds = revenue_predictor.predict(postal_code, horizon_days)
    return {
        "revenue_at_risk": preds["revenue_at_risk"],
        "predicted_ca_impact": preds["predicted_ca_impact"],
        "approval_rate": preds["approval_rate"],
        "friction_score": preds["friction_score"]
    }