import numpy as np
import pandas as pd
from typing import Optional, Any, List, Dict

class HybridEnsembleBundle:
    """
    Wrapper monolithique pour le modèle hybride.
    Supporte désormais l'explicabilité SHAP.
    """
    def __init__(self, rf_model=None, xgb_model=None, copod_model=None, meta_learner=None, 
                 preprocessor=None, feature_names=None, threshold=0.5,
                 full_pipeline=None, background_data=None, model_uuid=None):
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
        
        # Attributs pour l'explicabilité SHAP
        self.full_pipeline = full_pipeline          # Pipeline complet avec classifieur XGBoost
        self.background_data = background_data      # Échantillons background pour SHAP
        self.model_uuid = model_uuid                # Identifiant du modèle

    def set_copod_normalization(self, min_val, max_val):
        self.copod_min = min_val
        self.copod_max = max_val

    def predict_proba(self, X):
        """Logique de prédiction centralisée pour assurer la cohérence."""
        X_trans = self.preprocessor.transform(X)
        if hasattr(X_trans, 'toarray'):
            X_trans = X_trans.toarray()
            
        rf_score = self.rf_model.predict_proba(X_trans)[:, 1]
        xgb_score = self.xgb_model.predict_proba(X_trans)[:, 1]
        
        copod_raw = self.copod_model.decision_function(X_trans)
        if self.copod_min is not None and self.copod_max is not None:
            copod_score = (copod_raw - self.copod_min) / (self.copod_max - self.copod_min + 1e-6)
            copod_score = np.clip(copod_score, 0, 1)
        else:
            copod_score = copod_raw
        
        stacked = np.column_stack([rf_score, xgb_score, copod_score])
        return self.meta_learner.predict_proba(stacked)[:, 1]
    
    def get_xgb_classifier(self):
        """Retourne le classifieur XGBoost pour SHAP, s'il existe."""
        if self.full_pipeline is not None and hasattr(self.full_pipeline, 'named_steps'):
            classifier = self.full_pipeline.named_steps.get('classifier')
            if classifier is not None and hasattr(classifier, 'get_booster'):
                return classifier
        # Fallback : utiliser xgb_model direct (mais attention : peut être ONNX)
        if self.xgb_model is not None and hasattr(self.xgb_model, 'get_booster'):
            return self.xgb_model
        return None