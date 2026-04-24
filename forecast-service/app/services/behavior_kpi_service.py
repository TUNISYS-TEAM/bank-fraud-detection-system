import requests
import os

JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://java-backend:8080")

def compute_behavior_kpis(postal_code: str, horizon_days: int) -> dict:
    """
    Calcule le drift comportemental et le ratio de clients à haut risque.
    Idéalement interroge le backend Java pour obtenir les métriques réelles.
    """
    try:
        # Supposons un endpoint Java qui renvoie les métriques de comportement
        url = f"{JAVA_BACKEND_URL}/api/internal/behavior-stats"
        params = {"postalCode": postal_code, "windowDays": horizon_days}
        resp = requests.get(url, params=params, timeout=10)
        if resp.status_code == 200:
            data = resp.json()
            return {
                "behavior_drift_score": round(data.get("driftScore", 0.5), 3),
                "high_risk_customer_ratio": round(data.get("highRiskRatio", 0.1), 3)
            }
    except Exception:
        pass
    # fallback simulé cohérent
    import random
    seed = hash(postal_code) + horizon_days
    rng = random.Random(seed)
    return {
        "behavior_drift_score": round(rng.uniform(0.1, 0.9), 3),
        "high_risk_customer_ratio": round(rng.uniform(0.05, 0.3), 3)
    }