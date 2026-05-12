"""
Feature engineering pour l'entraînement des modèles de prévision.
Production-ready : features temporelles réelles + agrégations par zone.
"""

import numpy as np
from typing import List, Dict, Any
from collections import defaultdict
from datetime import datetime, timedelta

from app.utils.geo_utils import stable_postal_code_seed


def _parse_timestamp(tx: Dict[str, Any]):
    """Parse le timestamp d'une transaction."""
    ts = tx.get("timestamp")
    if ts is None:
        return None
    if isinstance(ts, str):
        try:
            return datetime.fromisoformat(ts.replace("Z", "+00:00"))
        except (ValueError, AttributeError):
            return None
    return ts


def _build_zone_aggregates(transactions: List[Dict[str, Any]], window_days: int = 30):
    """
    Calcule des agrégats par code postal sur une fenêtre glissante.
    """
    now = max(
        (t for tx in transactions if (t := _parse_timestamp(tx)) is not None),
        default=datetime.now()
    )
    cutoff = now - timedelta(days=window_days)

    zone_stats = defaultdict(lambda: {
        "tx_count": 0, "fraud_count": 0, "total_amount": 0.0,
        "fraud_amount": 0.0, "avg_fraud_score": 0.0, "score_count": 0
    })

    for tx in transactions:
        ts = _parse_timestamp(tx)
        if ts is None or ts < cutoff:
            continue

        pc = tx.get("postalCode", "0000")
        amount = float(tx.get("amount", 0))
        is_fraud = tx.get("finalDecision", False) or tx.get("isFraud", False)
        fraud_score = tx.get("fraudScore")

        stats = zone_stats[pc]
        stats["tx_count"] += 1
        stats["total_amount"] += amount

        if is_fraud:
            stats["fraud_count"] += 1
            stats["fraud_amount"] += amount

        if fraud_score is not None:
            stats["avg_fraud_score"] += float(fraud_score)
            stats["score_count"] += 1

    for pc, stats in zone_stats.items():
        if stats["score_count"] > 0:
            stats["avg_fraud_score"] /= stats["score_count"]
        else:
            stats["avg_fraud_score"] = 0.0

    return zone_stats


def _get_zone_stats(zone_stats, postal_code: str):
    """Récupère les stats d'une zone avec des valeurs par défaut."""
    stats = zone_stats.get(postal_code, {})
    return {
        "tx_count": stats.get("tx_count", 0),
        "fraud_count": stats.get("fraud_count", 0),
        "total_amount": stats.get("total_amount", 0.0),
        "fraud_amount": stats.get("fraud_amount", 0.0),
        "avg_fraud_score": stats.get("avg_fraud_score", 0.0),
    }


def build_features_for_loss(transactions: List[Dict[str, Any]]) -> np.ndarray:
    """Features pour le Loss Forecaster : features temporelles + zone."""
    zone_stats = _build_zone_aggregates(transactions)
    features = []

    for tx in transactions:
        pc = tx.get("postalCode", "0000")
        stats = _get_zone_stats(zone_stats, pc)
        amount = float(tx.get("amount", 0))
        fraud_score = tx.get("fraudScore") or 0.0

        features.append([
            stable_postal_code_seed(pc),    # 0: code postal encodé
            stats["tx_count"],              # 1: volume de transactions récent
            stats["fraud_count"],           # 2: nombre de fraudes récent
            stats["total_amount"] / 1000,   # 3: montant total normalisé
            stats["fraud_amount"] / 1000,   # 4: montant fraude normalisé
            stats["avg_fraud_score"],       # 5: score moyen de fraude
            amount / 1000,                  # 6: montant de la transaction
            float(fraud_score),             # 7: score de fraude
            7,                              # 8: horizon (fixe pour entraînement)
        ])

    return np.array(features, dtype=np.float32)


def build_target_loss(transactions: List[Dict[str, Any]]) -> np.ndarray:
    """Cible : montant de la fraude (finalDecision ou isFraud)."""
    targets = []
    for tx in transactions:
        if tx.get("finalDecision", False) or tx.get("isFraud", False):
            targets.append(float(tx.get("amount", 0)))
        else:
            targets.append(0.0)
    return np.array(targets, dtype=np.float32)


def build_features_for_hotspot(transactions: List[Dict[str, Any]]) -> np.ndarray:
    """Features pour le Hotspot Predictor."""
    zone_stats = _build_zone_aggregates(transactions)
    features = []

    for tx in transactions:
        pc = tx.get("postalCode", "0000")
        stats = _get_zone_stats(zone_stats, pc)
        fraud_score = tx.get("fraudScore") or 0.0
        amount = float(tx.get("amount", 0))

        features.append([
            stable_postal_code_seed(pc),    # 0: code postal
            stats["tx_count"],              # 1: volume
            stats["fraud_count"],           # 2: fraudes
            stats["total_amount"] / 1000,   # 3: montant total
            stats["fraud_amount"] / 1000,   # 4: montant fraude
            stats["avg_fraud_score"],       # 5: score moyen
            amount / 1000,                  # 6: montant transaction
            float(fraud_score),             # 7: fraud_score
            7,                              # 8: horizon
        ])

    return np.array(features, dtype=np.float32)


def build_target_hotspot(transactions: List[Dict[str, Any]]) -> np.ndarray:
    """Cible : fraudScore de la transaction (proxy du risque)."""
    targets = []
    for tx in transactions:
        score = tx.get("fraudScore")
        targets.append(float(score) if score is not None else 0.0)
    return np.array(targets, dtype=np.float32)


def build_features_for_revenue(transactions: List[Dict[str, Any]]) -> np.ndarray:
    """Features pour le Revenue Impact Predictor."""
    zone_stats = _build_zone_aggregates(transactions)
    features = []

    for tx in transactions:
        pc = tx.get("postalCode", "0000")
        stats = _get_zone_stats(zone_stats, pc)
        amount = float(tx.get("amount", 0))
        fraud_score = tx.get("fraudScore") or 0.0

        features.append([
            stable_postal_code_seed(pc),
            stats["tx_count"],
            stats["fraud_count"],
            stats["total_amount"] / 1000,
            stats["fraud_amount"] / 1000,
            stats["avg_fraud_score"],
            amount / 1000,
            float(fraud_score),
            7,
        ])

    return np.array(features, dtype=np.float32)


def build_target_revenue(transactions: List[Dict[str, Any]]) -> np.ndarray:
    """Cible multi-output : [revenue_at_risk, ca_impact, approval_rate, friction_score]."""
    targets = []
    for tx in transactions:
        amount = float(tx.get("amount", 0)) if (tx.get("finalDecision", False) or tx.get("isFraud", False)) else 0.0
        raR = amount
        ca_impact = raR * 0.5
        fraud_score = tx.get("fraudScore") or 0.0
        approval = 1.0 - min(float(fraud_score), 1.0)
        friction = min(float(fraud_score), 1.0)
        targets.append([raR, ca_impact, approval, friction])
    return np.array(targets, dtype=np.float32)