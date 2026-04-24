import random

class RevenueImpactPredictor:
    """
    Simule les modèles de baseline et d'impact CA.
    """
    def __init__(self, model_path: str = None):
        self.model_path = model_path

    def predict(self, postal_code: str, horizon_days: int) -> dict:
        seed = sum(ord(c) for c in postal_code)
        rng = random.Random(seed * 2 + horizon_days)
        revenue_at_risk = rng.uniform(5000, 200000) * (horizon_days / 7)
        ca_impact = revenue_at_risk * rng.uniform(0.2, 0.8)
        approval_rate = rng.uniform(0.75, 0.95)
        friction_score = rng.uniform(0.2, 0.7)
        return {
            "revenue_at_risk": round(revenue_at_risk, 2),
            "predicted_ca_impact": round(ca_impact, 2),
            "approval_rate": round(approval_rate, 3),
            "friction_score": round(friction_score, 3)
        }