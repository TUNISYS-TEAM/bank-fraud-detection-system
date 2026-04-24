import random

class HotspotPredictor:
    """
    Simule un modèle de prédiction de hotspots (score de risque par zone).
    """
    def __init__(self, model_path: str = None):
        self.model_path = model_path

    def predict(self, postal_code: str, horizon_days: int) -> dict:
        seed = hash(postal_code) % 10000
        rng = random.Random(seed + horizon_days)
        hotspot_score = rng.uniform(0.0, 1.0)
        cross_border_rate = rng.uniform(0.01, 0.2)
        return {
            "hotspot_score": round(hotspot_score, 3),
            "cross_border_anomaly_rate": round(cross_border_rate, 3)
        }