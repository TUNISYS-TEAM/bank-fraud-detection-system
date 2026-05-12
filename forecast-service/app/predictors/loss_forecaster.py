import logging
from typing import Optional

import joblib
import numpy as np

from app.config import LOSS_MODEL_FILE
from app.utils.geo_utils import stable_postal_code_seed

logger = logging.getLogger(__name__)


class LossForecaster:
    """
    Prévision des pertes futures par zone et horizon.
    Retourne None si le modèle n'est pas disponible.
    """

    def __init__(self, model_path: Optional[str] = None):
        self.model_path = model_path or LOSS_MODEL_FILE
        self.model = None
        self._load_model()

    def _load_model(self):
        try:
            self.model = joblib.load(self.model_path)
        except Exception:
            logger.warning("Modèle %s introuvable, KPI indisponible", self.model_path)
            self.model = None

    def reload(self):
        """Recharge le modèle depuis le disque (après entraînement)."""
        self._load_model()

    def _get_zone_stats(self, postal_code: str) -> dict:
        """Récupère les statistiques réelles d'une zone depuis les transactions."""
        try:
            from app.services.geo_kpi_service import _fetch_all_transactions
            transactions = _fetch_all_transactions()

            zone_txs = [tx for tx in transactions if tx.get("postalCode") == postal_code]

            if not zone_txs:
                return {
                    "tx_count": 0, "fraud_count": 0,
                    "total_amount": 0.0, "fraud_amount": 0.0,
                    "avg_fraud_score": 0.0
                }

            tx_count = len(zone_txs)
            fraud_count = sum(1 for tx in zone_txs if tx.get("isFraud") or tx.get("finalDecision"))
            total_amount = sum(float(tx.get("amount", 0)) for tx in zone_txs)
            fraud_amount = sum(
                float(tx.get("amount", 0)) for tx in zone_txs
                if tx.get("isFraud") or tx.get("finalDecision")
            )

            scores = [float(tx["fraudScore"]) for tx in zone_txs if tx.get("fraudScore") is not None]
            avg_fraud_score = sum(scores) / len(scores) if scores else 0.0

            return {
                "tx_count": tx_count,
                "fraud_count": fraud_count,
                "total_amount": total_amount,
                "fraud_amount": fraud_amount,
                "avg_fraud_score": avg_fraud_score,
            }
        except Exception:
            return {
                "tx_count": 0, "fraud_count": 0,
                "total_amount": 0.0, "fraud_amount": 0.0,
                "avg_fraud_score": 0.0
            }

    def predict(self, postal_code: Optional[str], horizon_days: int) -> dict:
        if self.model is None:
            return {"fraud_loss_forecast": None}

        features = self._build_features(postal_code, horizon_days)
        pred = float(self.model.predict(features)[0])
        return {
            "fraud_loss_forecast": round(max(0.0, pred), 2)
        }

    def _build_features(self, postal_code: Optional[str], horizon_days: int) -> np.ndarray:
        seed = stable_postal_code_seed(postal_code or "GLOBAL")

        if postal_code:
            stats = self._get_zone_stats(postal_code)
            tx_count = stats["tx_count"]
            fraud_count = stats["fraud_count"]
            total_amount = stats["total_amount"] / 1000  # normalisé
            fraud_amount = stats["fraud_amount"] / 1000
            avg_fraud_score = stats["avg_fraud_score"]
        else:
            tx_count = 0
            fraud_count = 0
            total_amount = 0.0
            fraud_amount = 0.0
            avg_fraud_score = 0.0

        return np.array([[
            seed,                 # 0: code postal encodé
            tx_count,             # 1: volume réel de transactions
            fraud_count,          # 2: nombre réel de fraudes
            total_amount,         # 3: montant total normalisé
            fraud_amount,         # 4: montant des fraudes normalisé
            avg_fraud_score,      # 5: score de fraude moyen
            0.0,                  # 6: amount (non applicable en prédiction)
            0.0,                  # 7: fraud_score (non applicable en prédiction)
            float(horizon_days),  # 8: horizon
        ]], dtype=np.float32)