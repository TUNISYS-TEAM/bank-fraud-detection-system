import random

class LossForecaster:
    """
    Simule un modèle de prévision des pertes (Prophet/XGBoost).
    À remplacer par le chargement d'un modèle sérialisé.
    """
    def __init__(self, model_path: str = None):
        self.model_path = model_path
        # Ici vous chargeriez votre modèle réel, ex: joblib.load(model_path)

    def predict(self, postal_code: str, horizon_days: int) -> dict:
        """
        Retourne un dictionnaire contenant la perte totale prévue, etc.
        Valeurs factices pour l'exemple.
        """
        # Générateur pseudo-aléatoire basé sur un hash du code postal pour reproductibilité
        seed = sum(ord(c) for c in postal_code)
        rng = random.Random(seed + horizon_days)
        base_loss = rng.uniform(1000, 50000)
        # Ajustement en fonction de l'horizon
        total_loss = base_loss * (horizon_days / 7)
        return {
            "fraud_loss_forecast": round(total_loss, 2),
            # ElC sera calculé plus tard par le service financier
        }