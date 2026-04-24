import random

def compute_behavior_kpis(postal_code: str, horizon_days: int) -> dict:
    seed = hash(postal_code) + horizon_days
    rng = random.Random(seed)
    drift = rng.uniform(0.1, 0.9)
    high_risk_ratio = rng.uniform(0.05, 0.3)
    return {
        "behavior_drift_score": round(drift, 3),
        "high_risk_customer_ratio": round(high_risk_ratio, 3)
    }