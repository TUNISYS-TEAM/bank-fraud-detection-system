import joblib
import numpy as np
from config import LOSS_MODEL_FILE

class LossForecaster:
    """
    Prévision des pertes futures par zone et horizon.
    Modèle : XGBoost Regressor entraîné sur les transactions historiques.
    """

    def __init__(self, model_path: str = None):
        self.model_path = model_path or LOSS_MODEL_FILE
        try:
            self.model = joblib.load(self.model_path)
        except FileNotFoundError:
            self.model = None

    def predict(self, postal_code: str, horizon_days: int) -> dict:
        if self.model is None:
            # fallback simulé
            import random
            seed = sum(ord(c) for c in postal_code)
            rng = random.Random(seed + horizon_days)
            total_loss = rng.uniform(1000, 50000) * (horizon_days / 7)
            return {"fraud_loss_forecast": round(total_loss, 2)}
        features = self._build_features(postal_code, horizon_days)
        pred = self.model.predict(features)[0]
        return {"fraud_loss_forecast": round(float(pred), 2)}

    def _build_features(self, postal_code: str, horizon_days: int) -> np.ndarray:
        # Exemple de features (à adapter selon vos features réelles d'entraînement)
        return np.array([[
            hash(postal_code) % 10000,  # code postal encodé
            horizon_days,                # horizon en jours
        ]])