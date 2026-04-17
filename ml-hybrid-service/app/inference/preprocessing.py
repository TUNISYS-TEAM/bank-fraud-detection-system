from typing import Dict, Any, Optional
import pandas as pd
import logging

logger = logging.getLogger(__name__)

class FeatureEngineer:
    """
    Gère la transformation brute des entrées JSON avant l'inférence.
    Note : Le gros du filtrage/imputation est ensuite fait par le pipeline chargé par joblib.
    """
    
    @staticmethod
    def build_features(transaction: Dict[str, Any], existing_behavior: Optional[Dict[str, Any]] = None) -> pd.DataFrame:
        """
        Fusionne les données transactionnelles et comportementales dans un DataFrame.
        """
        features = dict(transaction or {})
        
        if existing_behavior:
            # On ajoute les colonnes du comportement (ex: avg_amount_24h, etc.)
            features.update(existing_behavior)
            
        # Conversion en DataFrame (1 ligne) pour compatibilité avec scikit-learn
        return pd.DataFrame([features])

    @staticmethod
    def clean_raw_input(data: Dict[str, Any]) -> Dict[str, Any]:
        """Optionnel : Nettoyage basique des types (ex: types numériques)."""
        # Ex: s'assurer que TX_Amount est bien un float
        if 'TX_Amount' in data:
            try:
                data['TX_Amount'] = float(data['TX_Amount'])
            except (ValueError, TypeError):
                pass
        return data