import logging
from typing import Optional

import joblib
import numpy as np

from app.config import REVENUE_MODEL_FILE
from app.utils.geo_utils import stable_postal_code_seed

logger = logging.getLogger(__name__)


class RevenueImpactPredictor:
    """
    Prédit le chiffre d'affaires à risque et l'impact CA.
    Retourne None si le modèle n'est pas disponible.
    """

    def __init__(self, model_path: Optional[str] = None):
        self.model_path = model_path or REVENUE_MODEL_FILE
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
        horizon_days = max(1, horizon_days)

        if self.model is None:
            return self._empty_result()

        features = self._build_features(postal_code, horizon_days)

        try:
            preds = np.asarray(self.model.predict(features)[0]).ravel()

            if preds.size < 4:
                logger.warning(
                    "Sortie modèle revenue invalide pour postal_code=%s : %s valeurs reçues",
                    postal_code, preds.size,
                )
                return self._empty_result()

            return self._format_output(
                revenue_at_risk=float(preds[0]),
                predicted_ca_impact=float(preds[1]),
                approval_rate=float(preds[2]),
                friction_score=float(preds[3]),
            )

        except Exception as exc:
            logger.warning("Erreur pendant la prédiction revenue pour postal_code=%s : %s", postal_code, exc)
            return self._empty_result()

    def _empty_result(self) -> dict:
        return {
            "revenue_at_risk": None,
            "predicted_ca_impact": None,
            "approval_rate": None,
            "friction_score": None,
        }

    def _build_features(self, postal_code: Optional[str], horizon_days: int) -> np.ndarray:
        seed = stable_postal_code_seed(postal_code or "GLOBAL")

        if postal_code:
            stats = self._get_zone_stats(postal_code)
            tx_count = stats["tx_count"]
            fraud_count = stats["fraud_count"]
            total_amount = stats["total_amount"] / 1000
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
            tx_count,             # 1: volume réel
            fraud_count,          # 2: fraudes réelles
            total_amount,         # 3: montant total normalisé
            fraud_amount,         # 4: montant fraude normalisé
            avg_fraud_score,      # 5: score moyen
            0.0,                  # 6: amount
            0.0,                  # 7: fraud_score
            float(horizon_days),  # 8: horizon
        ]], dtype=np.float32)

    def _format_output(
        self,
        revenue_at_risk: float,
        predicted_ca_impact: float,
        approval_rate: float,
        friction_score: float,
    ) -> dict:
        return {
            "revenue_at_risk": round(max(0.0, revenue_at_risk), 2),
            "predicted_ca_impact": round(max(0.0, predicted_ca_impact), 2),
            "approval_rate": round(min(max(approval_rate, 0.0), 1.0), 3),
            "friction_score": round(min(max(friction_score, 0.0), 1.0), 3),
        }