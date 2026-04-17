import numpy as np
import pandas as pd
from typing import Dict, Any, List
import logging
from app.models.bundles import HybridEnsembleBundle

logger = logging.getLogger(__name__)

class HybridEngine:
    @staticmethod
    def predict(artifact_bundle: Dict[str, Any], features_raw: pd.DataFrame) -> Dict[str, Any]:
        """
        Exécute l'inférence selon le type de modèle chargé.
        artifact_bundle: {"model": ..., "preprocessor": ...}
        """
        try:
            model = artifact_bundle["model"]
            preprocessor = artifact_bundle["preprocessor"]

            # 1. Cas HYBRID_ENSEMBLE (bundle monolithique)
            if isinstance(model, HybridEnsembleBundle):
                return HybridEngine._predict_hybrid(model, features_raw)
            
            # 2. Cas Standalone (Autoencoder, ECOD, COPOD)
            # On utilise le preprocessor chargé séparément s'il existe
            elif hasattr(model, "predict_proba"):
                return HybridEngine._predict_standalone_anomaly(model, preprocessor, features_raw)
            
            else:
                raise ValueError(f"Type de modèle non supporté : {type(model)}")

        except Exception as e:
            logger.error(f"Erreur d'inférence : {e}")
            raise

    @staticmethod
    def _compute_risk_level(score: float, threshold: float) -> str:
        """
        Taxonomie de risque unifiée pour toutes les branches d'inférence.
        Alignée avec les niveaux attendus par le backend Java.
        """
        if score >= 0.9:
            return "CRITICAL"
        if score >= threshold:
            return "HIGH"
        if score >= threshold / 2:
            return "MEDIUM"
        return "LOW"

    @staticmethod
    def _predict_hybrid(bundle: HybridEnsembleBundle, features_raw: pd.DataFrame) -> Dict[str, Any]:
        # A. Preprocessing (interne au bundle)
        X_trans = bundle.preprocessor.transform(features_raw)
        if hasattr(X_trans, 'toarray'): X_trans = X_trans.toarray()
        
        # B. Base models predictions (Logique synchronisée avec le training)
        rf_score = bundle.rf_model.predict_proba(X_trans)[:, 1]
        xgb_score = bundle.xgb_model.predict_proba(X_trans)[:, 1]
        
        # Logique COPOD avec normalisation apprise
        copod_raw = bundle.copod_model.decision_function(X_trans)
        if bundle.copod_min is not None and bundle.copod_max is not None:
            copod_score = (copod_raw - bundle.copod_min) / (bundle.copod_max - bundle.copod_min + 1e-6)
            copod_score = np.clip(copod_score, 0, 1)
        else:
            copod_score = copod_raw
        
        # C. Meta-learner
        meta_features = np.column_stack([rf_score, xgb_score, copod_score])
        final_proba = bundle.meta_learner.predict_proba(meta_features)[:, 1]
        
        score = float(final_proba[0])
        threshold = getattr(bundle, 'threshold', 0.5)
        
        # D. Construction de la réponse (Alignement Java)
        return {
            "fraud_score": score,
            "risk_level": HybridEngine._compute_risk_level(score, threshold),
            "alert_type": "HYBRID_ENSEMBLE",
            "supervised_score": float((rf_score[0] + xgb_score[0]) / 2),
            "anomaly_score": float(copod_score[0]),
            "fraud_reason": {
                "rf_score": float(rf_score[0]),
                "xgb_score": float(xgb_score[0]),
                "anomaly_score": float(copod_score[0]),
                "normalization": {"min": bundle.copod_min, "max": bundle.copod_max}
            },
            "behavioral_signals": {
                "is_outlier": bool(copod_score[0] > 0.8),
                "consensus": bool(rf_score[0] > threshold and xgb_score[0] > threshold)
            }
        }

    @staticmethod
    def _predict_standalone_anomaly(model: Any, preprocessor: Any, features_raw: pd.DataFrame) -> Dict[str, Any]:
        # Application du préprocesseur externe si disponible
        X_proc = features_raw
        if preprocessor:
            X_proc = preprocessor.transform(features_raw)
            if hasattr(X_proc, 'toarray'): X_proc = X_proc.toarray()
            
        # predict_proba retourne un ndarray (N,) — on extrait le premier score (inférence unitaire)
        scores = model.predict_proba(X_proc)
        score = float(scores[0]) if len(scores) > 0 else 0.0
        threshold = getattr(model, 'threshold_', 0.5)
        
        return {
            "fraud_score": score,
            "risk_level": HybridEngine._compute_risk_level(score, threshold),
            "alert_type": getattr(model, 'name', 'ANOMALY').upper(),
            "supervised_score": 0.0,
            "anomaly_score": score,
            "fraud_reason": {"anomaly_score": score, "has_pipeline": preprocessor is not None},
            "behavioral_signals": {"deviation_rank": score}
        }