import logging
from typing import Optional

import joblib
import numpy as np

from app.config import HOTSPOT_MODEL_FILE

logger = logging.getLogger(__name__)


class HotspotPredictor:
    """
    Prédit le score de risque par zone (hotspot) et le taux d'anomalie transfrontalière.
    Retourne None si le modèle n'est pas disponible.
    """

    def __init__(self, model_path: str = None):
        self.model_path = model_path or HOTSPOT_MODEL_FILE
        self.hotspot_model = None
        self.cross_border_model = None
        self._load_model()

    def _load_model(self):
        try:
            self.hotspot_model = joblib.load(self.model_path)
        except Exception:
            logger.warning("Modèle hotspot %s introuvable, KPI indisponible", self.model_path)
            self.hotspot_model = None

    def reload(self):
        """Recharge le modèle depuis le disque (après entraînement)."""
        self._load_model()

    def predict(self, postal_code: Optional[str], horizon_days: int) -> dict:
        if self.hotspot_model is None:
            return {
                "hotspot_score": None,
                "cross_border_anomaly_rate": None,
            }

        features = self._build_features(postal_code, horizon_days)
        hotspot_score = float(self.hotspot_model.predict(features)[0])
        return {
            "hotspot_score": round(min(max(hotspot_score, 0), 1), 3),
            "cross_border_anomaly_rate": None,
        }

    def _get_zone_stats(self, postal_code: str) -> dict:
        """Récupère les statistiques réelles d'une zone depuis les transactions."""
        try:
            from app.services.geo_kpi_service import _fetch_all_transactions
            transactions = _fetch_all_transactions()
            
            zone_txs = [tx for tx in transactions if tx.get("postalCode") == postal_code]
            
            if not zone_txs:
                return {"tx_count": 0, "fraud_count": 0, "total_amount": 0, "fraud_amount": 0, "avg_fraud_score": 0}
            
            tx_count = len(zone_txs)
            fraud_count = sum(1 for tx in zone_txs if tx.get("isFraud") or tx.get("finalDecision"))
            total_amount = sum(float(tx.get("amount", 0)) for tx in zone_txs)
            fraud_amount = sum(float(tx.get("amount", 0)) for tx in zone_txs if tx.get("isFraud") or tx.get("finalDecision"))
            
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
            return {"tx_count": 0, "fraud_count": 0, "total_amount": 0, "fraud_amount": 0, "avg_fraud_score": 0}

    def _build_features(self, postal_code: Optional[str], horizon_days: int) -> np.ndarray:
        seed = hash(postal_code or "GLOBAL") % 10000
        
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
            seed,               # 0: code postal encodé
            tx_count,           # 1: volume réel de transactions dans la zone
            fraud_count,        # 2: nombre réel de fraudes dans la zone
            total_amount,       # 3: montant total normalisé
            fraud_amount,       # 4: montant des fraudes normalisé
            avg_fraud_score,    # 5: score de fraude moyen
            0.0,                # 6: fraud_score (non applicable en prédiction)
            0.0,                # 7: amount (non applicable en prédiction)
            float(horizon_days),# 8: horizon
        ]], dtype=np.float32)