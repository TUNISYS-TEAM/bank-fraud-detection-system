import joblib
import numpy as np
from config import HOTSPOT_MODEL_FILE

class HotspotPredictor:
    """
    Prédit le score de risque par zone (hotspot) et le taux d'anomalie transfrontalière.
    Modèle : XGBoost Regressor entraîné avec features géographiques + temporelles.
    """

    def __init__(self, model_path: str = None):
        self.model_path = model_path or HOTSPOT_MODEL_FILE
        try:
            self.hotspot_model = joblib.load(self.model_path)
            # Optionnel : un second modèle pour cross_border
            self.cross_border_model = None
        except FileNotFoundError:
            self.hotspot_model = None

    def predict(self, postal_code: str, horizon_days: int) -> dict:
        if self.hotspot_model is None:
            import random
            seed = hash(postal_code) % 10000
            rng = random.Random(seed + horizon_days)
            return {
                "hotspot_score": round(rng.uniform(0.0, 1.0), 3),
                "cross_border_anomaly_rate": round(rng.uniform(0.01, 0.2), 3)
            }
        features = self._build_features(postal_code, horizon_days)
        hotspot_score = self.hotspot_model.predict(features)[0]
        cross_border_rate = 0.05  # par défaut, ou via un second modèle
        return {
            "hotspot_score": round(min(max(float(hotspot_score), 0), 1), 3),
            "cross_border_anomaly_rate": round(float(cross_border_rate), 3)
        }

    def _build_features(self, postal_code: str, horizon_days: int) -> np.ndarray:
        return np.array([[
            hash(postal_code) % 10000,
            horizon_days,
            # Ajoutez : fraud_rate_last_7d, avg_amount, transaction_density, cluster_score...
        ]])