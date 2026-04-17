import numpy as np
import pandas as pd
from typing import Optional, Any, List

class RankNormalizer:
    """
    Mappe les scores d'anomalie bruts sur [0, 1].
    Doit être identique à la version utilisée lors de l'entraînement.
    """
    def __init__(self):
        self._sorted_scores: Optional[np.ndarray] = None

    def transform(self, scores: np.ndarray) -> np.ndarray:
        if self._sorted_scores is None:
            return scores
        scores = np.asarray(scores).reshape(-1)
        ranks = np.searchsorted(self._sorted_scores, scores, side="right")
        return ranks / len(self._sorted_scores)

class HybridEnsembleBundle:
    """
    Wrapper monolithique pour le modèle hybride.
    Synchronisé avec ml-pipeline/scripts/train_hybrid_ensemble.py
    """
    def __init__(self, rf_model=None, xgb_model=None, copod_model=None, meta_learner=None, 
                 preprocessor=None, feature_names=None, threshold=0.5):
        self.rf_model = rf_model
        self.xgb_model = xgb_model
        self.copod_model = copod_model
        self.meta_learner = meta_learner
        self.preprocessor = preprocessor
        self.feature_names = feature_names
        self.threshold = threshold
        self.copod_min = None
        self.copod_max = None
        self.name = "HYBRID_ENSEMBLE"

    def set_copod_normalization(self, min_val, max_val):
        self.copod_min = min_val
        self.copod_max = max_val

    def predict_proba(self, X):
        """Logique de prédiction centralisée pour assurer la cohérence."""
        X_trans = self.preprocessor.transform(X)
        if hasattr(X_trans, 'toarray'): X_trans = X_trans.toarray()
            
        rf_score = self.rf_model.predict_proba(X_trans)[:, 1]
        xgb_score = self.xgb_model.predict_proba(X_trans)[:, 1]
        
        # Logique COPOD avec normalisation apprise
        copod_raw = self.copod_model.decision_function(X_trans)
        if self.copod_min is not None and self.copod_max is not None:
            copod_score = (copod_raw - self.copod_min) / (self.copod_max - self.copod_min + 1e-6)
            copod_score = np.clip(copod_score, 0, 1)
        else:
            copod_score = copod_raw
        
        # Meta-fusion
        stacked = np.column_stack([rf_score, xgb_score, copod_score])
        return self.meta_learner.predict_proba(stacked)[:, 1]

class AnomalyAutoencoder:
    """Squelette pour charger l'Autoencoder entraîné."""
    def __init__(self, **kwargs):
        self._model = None
        self._normalizer = RankNormalizer()
        self.threshold_ = 0.5
        self.name = "Autoencoder"

    def predict_proba(self, X) -> np.ndarray:
        if hasattr(self, '_raw_mse'): 
             return self._normalizer.transform(self._raw_mse(X))
        return np.zeros(len(X))

class ECODFraudModel:
    """Squelette pour charger le modèle ECOD."""
    def __init__(self, **kwargs):
        self._model = None
        self._normalizer = RankNormalizer()
        self.threshold_ = 0.5
        self.name = "ECOD"

    def predict_proba(self, X) -> np.ndarray:
        raw = self._model.decision_function(X)
        return self._normalizer.transform(raw)

class COPODFraudModel:
    """Squelette pour charger le modèle COPOD standalone."""
    def __init__(self, **kwargs):
        self._model = None
        self._normalizer = RankNormalizer()
        self.threshold_ = 0.5
        self.name = "COPOD"

    def predict_proba(self, X) -> np.ndarray:
        raw = self._model.decision_function(X)
        return self._normalizer.transform(raw)