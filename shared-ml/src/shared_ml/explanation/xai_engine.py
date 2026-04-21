"""
Moteur d'explicabilité hyper-intelligent v5.1

Ce module fournit un moteur d'explication avancé pour la détection de fraude,
combinant SHAP, l'apprentissage profond et l'analyse statistique.
"""

import re
import json
import sys
import numpy as np
import pandas as pd
from typing import Dict, List, Optional, Any, Tuple
from datetime import datetime

import shap
import torch
import torch.nn as nn
import torch.nn.functional as F

try:
    from captum.attr import IntegratedGradients, DeepLift, GradientShap
    CAPTUM_AVAILABLE = True
except ImportError:
    CAPTUM_AVAILABLE = False

TORCH_AVAILABLE = True

__all__ = ['FraudExplainabilityEngine']


class DeepLearningExplainer:
    """
    Expliqueur avancé pour l'apprentissage profond utilisant Captum.
    Supporte les gradients intégrés, DeepLIFT et GradientSHAP.
    """

    def __init__(self, model_path: str, input_dim: int, device: str = "cpu", method: str = "integrated_gradients"):
        if not TORCH_AVAILABLE:
            raise ImportError("PyTorch et Captum sont requis pour l'explicabilité par apprentissage profond")
        
        self.device = torch.device(device)
        self.input_dim = input_dim
        self.method = method
        
        # Chargement du modèle
        try:
            self.model = torch.jit.load(model_path, map_location=self.device)
        except:
            # Tentative de chargement en tant que state dict
            self.model = self._load_state_dict_model(model_path, input_dim)
        
        self.model.eval()
        
        # Initialisation de la méthode d'attribution
        if method == "integrated_gradients":
            self.attributor = IntegratedGradients(self.model)
        elif method == "deep_lift":
            self.attributor = DeepLift(self.model)
        elif method == "gradient_shap":
            self.attributor = GradientShap(self.model)
        else:
            self.attributor = IntegratedGradients(self.model)
    
    def _load_state_dict_model(self, model_path: str, input_dim: int) -> nn.Module:
        """Charge un modèle PyTorch à partir d'un dictionnaire d'états."""
        # MLP simple comme architecture par défaut
        class SimpleMLP(nn.Module):
            def __init__(self, input_dim):
                super().__init__()
                self.net = nn.Sequential(
                    nn.Linear(input_dim, 128),
                    nn.ReLU(),
                    nn.Dropout(0.2),
                    nn.Linear(128, 64),
                    nn.ReLU(),
                    nn.Dropout(0.2),
                    nn.Linear(64, 32),
                    nn.ReLU(),
                    nn.Linear(32, 1)
                )
            
            def forward(self, x):
                return self.net(x)
        
        model = SimpleMLP(input_dim)
        model.load_state_dict(torch.load(model_path, map_location=self.device))
        return model
    
    def predict_anomaly_score(self, features: np.ndarray) -> float:
        """Passage avant : retourne la probabilité d'anomalie."""
        tensor = torch.from_numpy(features).float().to(self.device).unsqueeze(0)
        with torch.no_grad():
            output = self.model(tensor)
        
        if isinstance(output, torch.Tensor):
            if output.numel() == 1:
                return float(torch.sigmoid(output).item())
            return float(output.mean().item())
        return float(output)
    
    def get_feature_attributions(self, features: np.ndarray) -> np.ndarray:
        """Obtient les attributions des caractéristiques en utilisant Captum."""
        tensor = torch.from_numpy(features).float().to(self.device).unsqueeze(0)
        baselines = torch.zeros_like(tensor)
        
        if self.method == "gradient_shap":
            # GradientSHAP a besoin de plusieurs références
            baselines = torch.randn(10, *tensor.shape[1:]).to(self.device)
            attributions = self.attributor.attribute(tensor, baselines, target=0)
        else:
            attributions = self.attributor.attribute(tensor, baselines, target=0)
        
        return attributions.squeeze().cpu().detach().numpy()


class FraudExplainabilityEngine:
    """
    Expliqueur universel qui sélectionne automatiquement la meilleure méthode pour tout modèle.
    - Modèles basés sur arbres : SHAP TreeExplainer
    - Réseaux de neurones : Captum Integrated Gradients / DeepLIFT
    - Autres modèles : SHAP KernelExplainer
    - Se rabat toujours sur l'analyse statistique si tout échoue
    """

    def __init__(self, model, feature_names: List[str],
                 background_data: np.ndarray = None, threshold: float = 0.5,
                 config_path: str = None,
                 dl_model_path: str = None, dl_input_dim: int = None,
                 dl_method: str = "integrated_gradients"):
        self.model = model
        self.feature_names = feature_names
        self.threshold = threshold
        self.config = self._load_config(config_path)
        self.explainer = None
        self.explainer_type = None
        self.dl_explainer = None
        
        # Détection du type de modèle et initialisation de l'expliqueur approprié
        model_type = self._detect_model_type(model)
        print(f"Type de modèle détecté : {model_type}", file=sys.stderr)
        
        # Initialisation de l'expliqueur SHAP pour les modèles non-DL
        if model_type in ['tree', 'linear', 'unknown']:
            self._init_shap_explainer(model, background_data)
        
        # Initialisation de l'expliqueur d'apprentissage profond (si fourni)
        if TORCH_AVAILABLE and dl_model_path and dl_input_dim:
            try:
                self.dl_explainer = DeepLearningExplainer(
                    dl_model_path, dl_input_dim, device="cpu", method=dl_method
                )
                print(f"Expliqueur d'apprentissage profond initialisé (méthode : {dl_method})", file=sys.stderr)
            except Exception as e:
                print(f"Échec de l'initialisation de l'expliqueur DL : {e}", file=sys.stderr)

    def _detect_model_type(self, model) -> str:
        """Détecte le type de modèle avec une grande précision."""
        model_str = str(type(model)).lower()
        
        # Modèles basés sur arbres
        if any(x in model_str for x in ['lightgbm', 'lgbm', 'xgboost', 'xgb', 'catboost', 
                                         'randomforest', 'random_forest', 'gradientboosting']):
            return 'tree'
        
        # Modèles linéaires
        if any(x in model_str for x in ['linear', 'logistic', 'ridge', 'lasso', 'svc', 'svm']):
            return 'linear'
        
        # Réseaux de neurones (scikit-learn MLP)
        if 'mlp' in model_str or 'neural' in model_str:
            return 'neural'
        
        # Tentative de détection par méthodes
        if hasattr(model, 'get_booster'):
            return 'tree'
        if hasattr(model, 'trees_'):
            return 'tree'
        
        return 'unknown'

    def _init_shap_explainer(self, model, background_data: np.ndarray):
        """Initialise l'expliqueur SHAP avec une chaîne de repli intelligente."""
        
        # Stratégie 1 : TreeExplainer avec intervention (fonctionne avec de petits backgrounds)
        if background_data is not None and len(background_data) > 0:
            try:
                self.explainer = shap.TreeExplainer(
                    model, background_data, feature_perturbation="interventional"
                )
                self.explainer_type = "TreeExplainer(interventionnel)"
                print(f"SHAP {self.explainer_type} initialisé", file=sys.stderr)
                return
            except Exception as e:
                print(f"Échec de TreeExplainer(interventionnel) : {e}", file=sys.stderr)
        
        # Stratégie 2 : TreeExplainer sans background (fonctionne toujours)
        try:
            self.explainer = shap.TreeExplainer(model)
            self.explainer_type = "TreeExplainer(sans_background)"
            print(f"SHAP {self.explainer_type} initialisé", file=sys.stderr)
            return
        except Exception as e:
            print(f"Échec de TreeExplainer(sans_background) : {e}", file=sys.stderr)
        
        # Stratégie 3 : KernelExplainer (fonctionne avec tout modèle, plus lent mais universel)
        if background_data is not None and len(background_data) >= 10:
            try:
                bg_sample = background_data[:min(100, len(background_data))]
                
                # Définition de la fonction de prédiction
                if hasattr(model, 'predict_proba'):
                    def predict_fn(x):
                        return model.predict_proba(x)
                elif hasattr(model, 'decision_function'):
                    def predict_fn(x):
                        return model.decision_function(x)
                elif hasattr(model, 'predict'):
                    def predict_fn(x):
                        pred = model.predict(x)
                        if pred.ndim == 1:
                            pred = np.column_stack([1 - pred, pred])
                        return pred
                else:
                    raise ValueError("Le modèle n'a pas de méthode de prédiction")
                
                self.explainer = shap.KernelExplainer(predict_fn, bg_sample)
                self.explainer_type = "KernelExplainer"
                print(f"SHAP {self.explainer_type} initialisé", file=sys.stderr)
                return
            except Exception as e:
                print(f"Échec de KernelExplainer : {e}", file=sys.stderr)
        
        print("ATTENTION : Aucun explainer SHAP n'a pu être initialisé. Utilisation de l'analyse statistique uniquement.", file=sys.stderr)

    def _load_config(self, config_path: Optional[str]) -> Dict:
        default_config = {
            "factor_display": {
                "amount": "Montant",
                "velocity": "Fréquence",
                "location": "Localisation",
                "pattern": "Pattern",
                "history": "Historique",
                "risk_score": "Score risque",
                "time": "Horaire",
                "amount_ratio": "Ratio montant",
                "dl_anomaly": "Anomalie profonde",
                "neural_network": "Réseau neuronal"
            },
            "statistical_thresholds": {
                "z_score_anomaly": 1.8,
                "z_score_extreme": 3.0,
                "velocity_seconds": 300,
                "velocity_extreme": 60,
                "amount_ratio_high": 3.0,
                "frequency_low": 0.15,
                "risk_score_high": 15,
                "risk_score_extreme": 30,
                "late_hour_start": 22,
                "late_hour_end": 6
            },
            "dl_anomaly_weight": 0.3,
            "dl_contribution_threshold": 0.05,
            "shap_contribution_threshold": 0.02
        }
        if config_path:
            try:
                with open(config_path, 'r') as f:
                    user_config = json.load(f)
                default_config.update(user_config)
            except Exception as e:
                print(f"Attention : Impossible de charger la configuration : {e}", file=sys.stderr)
        return default_config

    def explain_fraud(self, features: np.ndarray, fraud_score: float,
                      transaction_data: Dict, behavior_data: Dict) -> Dict:
        """Génère une explication intelligente en utilisant la meilleure méthode disponible."""
        contributions = []

        # 1. Contributions SHAP (si disponibles)
        if self.explainer:
            shap_contribs = self._get_shap_explanations(features)
            if shap_contribs:
                contributions.extend(self._build_contributions_from_shap(shap_contribs, fraud_score))

        # 2. Contributions de l'apprentissage profond (si disponibles)
        if self.dl_explainer:
            dl_contribs = self._get_dl_contributions(features, fraud_score)
            if dl_contribs:
                contributions.extend(dl_contribs)

        # 3. Contributions basées sur des règles statistiques (toujours calculées comme repli)
        stat_contribs = self._intelligent_behavior_analysis(behavior_data, transaction_data, fraud_score)
        contributions.extend(stat_contribs)

        # Fusion et tri
        contributions = self._merge_contributions(contributions)
        contributions.sort(key=lambda x: x["impact_points"], reverse=True)

        # Détermination de la source pour la réponse
        if self.dl_explainer and self.explainer:
            source = "hybride(shap+dl+statistique)"
        elif self.dl_explainer:
            source = "apprentissage_profond+statistique"
        elif self.explainer:
            source = f"shap({self.explainer_type})+statistique"
        else:
            source = "statistique_seule"

        return self._build_response(contributions, fraud_score, transaction_data, behavior_data, source=source)

    def _get_shap_explanations(self, features: np.ndarray) -> Dict[str, float]:
        """Extrait les valeurs SHAP avec gestion des erreurs."""
        if not self.explainer:
            return {}
        
        try:
            shap_values = self.explainer.shap_values(features.reshape(1, -1))
            
            # Gestion des différents formats de sortie
            if isinstance(shap_values, list):
                # Classification binaire : prendre la classe positive
                shap_values = shap_values[1] if len(shap_values) > 1 else shap_values[0]
            if isinstance(shap_values, np.ndarray) and len(shap_values.shape) > 1:
                shap_vals = shap_values[0] if shap_values.shape[0] == 1 else shap_values
            else:
                shap_vals = shap_values
            
            # Assurer un tableau 1D
            if isinstance(shap_vals, np.ndarray) and shap_vals.ndim > 1:
                shap_vals = shap_vals.flatten()
            
            total_abs = np.sum(np.abs(shap_vals))
            if total_abs == 0:
                return {}
            
            contributions = {}
            threshold = self.config["shap_contribution_threshold"]
            
            for i, name in enumerate(self.feature_names):
                if i >= len(shap_vals):
                    break
                val = float(shap_vals[i])
                if abs(val) > threshold:
                    clean_name = self._clean_feature_name(name)
                    impact = int(100 * abs(val) / total_abs)
                    contributions[clean_name] = impact
            
            return contributions
            
        except Exception as e:
            print(f"Échec de l'extraction SHAP : {e}", file=sys.stderr)
            return {}

    def _get_dl_contributions(self, features: np.ndarray, fraud_score: float) -> List[Dict]:
        """Obtient les contributions de l'apprentissage profond en utilisant Captum."""
        if not self.dl_explainer:
            return []
        
        try:
            dl_anomaly = self.dl_explainer.predict_anomaly_score(features)
            attributions = self.dl_explainer.get_feature_attributions(features)
            
            contributions = [{
                "factor": "DL_ANOMALY",
                "impact_points": int(dl_anomaly * 60),
                "description": "Détection d'anomalie par apprentissage profond",
                "detail": f"Le réseau neuronal a détecté un motif anormal (confiance : {dl_anomaly:.1%})",
                "type": "deep_learning",
                "severity": "high" if dl_anomaly > 0.7 else "medium"
            }]
            
            # Ajout des caractéristiques les plus contributives
            attr_threshold = self.config["dl_contribution_threshold"]
            for idx, attr_val in enumerate(attributions):
                if abs(attr_val) > attr_threshold and idx < len(self.feature_names):
                    clean_name = self._clean_feature_name(self.feature_names[idx])
                    factor = self._map_feature_to_factor(clean_name)
                    if factor:
                        points_attr = int(min(40, abs(attr_val) * 20))
                        contributions.append({
                            "factor": factor,
                            "impact_points": points_attr,
                            "description": f"Réseau neuronal : {clean_name}",
                            "detail": f"Le modèle d'apprentissage profond a signalé {clean_name} (importance : {abs(attr_val):.3f})",
                            "type": "deep_learning",
                            "severity": "medium"
                        })
            
            return contributions
            
        except Exception as e:
            print(f"Échec de l'attribution DL : {e}", file=sys.stderr)
            return []

    # ----------------------------------------------------------------------
    # Analyse statistique basée sur des règles
    # ----------------------------------------------------------------------
    def _intelligent_behavior_analysis(self, behavior: Dict, transaction: Dict, fraud_score: float) -> List[Dict]:
        """Analyse avancée utilisant TOUS les signaux comportementaux disponibles."""
        contributions = []
        thresholds = self.config["statistical_thresholds"]

        total_tx = self._safe_int(behavior.get('client_total_transactions', 0))
        amount = self._safe_float(transaction.get('TX_Amount', transaction.get('amount', 0)))
        avg_amount = self._safe_float(behavior.get('client_AVG_TX_Amount', 0))
        z_score = self._safe_float(behavior.get('client_amount_z_score', 0))
        time_since = self._safe_float(behavior.get('client_time_since_last_tx', 9999))
        risk_score = self._safe_float(behavior.get('client_risk_score', 0))
        amount_ratio = self._safe_float(behavior.get('client_amount_max_ratio', 1.0))

        is_unusual_region = bool(behavior.get('client_is_unusual_region', False))
        is_unusual_channel = bool(behavior.get('client_is_unusual_channel', False))
        is_unusual_category = bool(behavior.get('client_is_unusual_category', False))
        region_freq = self._safe_float(behavior.get('client_region_freq', 1.0))
        channel_freq = self._safe_float(behavior.get('client_channel_freq', 1.0))
        category_freq = self._safe_float(behavior.get('client_category_freq', 1.0))
        usual_region = behavior.get('client_usual_region', 'Inconnue')
        usual_channel = behavior.get('client_usual_channel', 'Inconnu')
        usual_category = behavior.get('client_usual_category', 'Inconnue')
        total_days = self._safe_float(behavior.get('client_total_days_since_first', 0))
        is_new_max = bool(behavior.get('client_is_new_max', False))

        # Analyse de l'historique
        if total_tx == 0:
            contributions.append({
                "factor": "NO_HISTORY",
                "impact_points": int(25 + fraud_score * 25),
                "description": "Première transaction - Pas d'historique",
                "detail": "Il s'agit de la première transaction du client. Aucun profil comportemental n'existe.",
                "type": "statistical",
                "severity": "high"
            })
        elif total_tx == 1:
            contributions.append({
                "factor": "LIMITED_HISTORY",
                "impact_points": int(15 + fraud_score * 15),
                "description": "Historique limité des transactions",
                "detail": f"Seulement {total_tx} transaction antérieure. Données insuffisantes pour un profilage fiable.",
                "type": "statistical",
                "severity": "medium"
            })
        else:
            contributions.append({
                "factor": "ESTABLISHED_CLIENT",
                "impact_points": 5,
                "description": "Client établi",
                "detail": f"Client avec {total_tx} transactions historiques sur {total_days:.0f} jours",
                "type": "statistical",
                "severity": "low"
            })

        # Anomalie de montant
        if abs(z_score) > thresholds["z_score_anomaly"]:
            is_extreme = abs(z_score) > thresholds["z_score_extreme"]
            points = min(60, int(20 + abs(z_score) * 10))
            if avg_amount > 0:
                deviation_pct = (amount - avg_amount) / avg_amount * 100
                direction = "supérieur" if deviation_pct > 0 else "inférieur"
                detail = f"Montant {direction} à la moyenne de {abs(deviation_pct):.0f}% (moyenne : {avg_amount:.0f})"
            else:
                detail = f"Anomalie de montant détectée (z-score={z_score:.1f})"
            contributions.append({
                "factor": "AMOUNT_ANOMALY",
                "impact_points": points,
                "description": f"Anomalie de montant - {'extrême' if is_extreme else 'significative'}",
                "detail": detail,
                "type": "statistical",
                "severity": "high" if is_extreme else "medium"
            })
        elif amount_ratio > thresholds["amount_ratio_high"] and total_tx > 0:
            points = int(30 + (amount_ratio - thresholds["amount_ratio_high"]) * 10)
            contributions.append({
                "factor": "AMOUNT_RATIO",
                "impact_points": points,
                "description": "Montant anormalement élevé",
                "detail": f"Le montant est {amount_ratio:.1f} fois supérieur au maximum historique",
                "type": "statistical",
                "severity": "high"
            })

        # Anomalie de vélocité
        if 0 < time_since < thresholds["velocity_seconds"] and total_tx > 0:
            is_extreme = time_since < thresholds["velocity_extreme"]
            points = int(20 + (thresholds["velocity_seconds"] - time_since) / thresholds["velocity_seconds"] * 40)
            contributions.append({
                "factor": "VELOCITY",
                "impact_points": points,
                "description": f"Vélocité des transactions - {'extrême' if is_extreme else 'rapide'}",
                "detail": f"Seulement {time_since:.0f} secondes depuis la transaction précédente",
                "type": "statistical",
                "severity": "high" if is_extreme else "medium"
            })

        # Anomalie de localisation
        if is_unusual_region:
            points = 35
            detail = f"Transaction depuis une localisation inhabituelle (habituelle : {usual_region})" if usual_region != 'Inconnue' else "Transaction depuis une localisation inhabituelle"
            contributions.append({
                "factor": "LOCATION_ANOMALY",
                "impact_points": points,
                "description": "Localisation inhabituelle",
                "detail": detail,
                "type": "statistical",
                "severity": "high"
            })
        elif region_freq < thresholds["frequency_low"] and region_freq > 0 and total_tx > 3:
            points = int(20 + (1 - region_freq) * 20)
            contributions.append({
                "factor": "LOCATION_ANOMALY",
                "impact_points": points,
                "description": "Localisation rare",
                "detail": f"Cette localisation n'apparaît que dans {region_freq*100:.0f}% des transactions",
                "type": "statistical",
                "severity": "medium"
            })

        # Anomalie de canal
        if is_unusual_channel:
            points = 30
            detail = f"Canal de transaction inhabituel (habituel : {usual_channel})" if usual_channel != 'Inconnu' else "Canal de transaction inhabituel"
            contributions.append({
                "factor": "PATTERN",
                "impact_points": points,
                "description": "Canal inhabituel",
                "detail": detail,
                "type": "statistical",
                "severity": "high"
            })
        elif channel_freq < thresholds["frequency_low"] and channel_freq > 0 and total_tx > 3:
            points = int(15 + (1 - channel_freq) * 15)
            contributions.append({
                "factor": "PATTERN",
                "impact_points": points,
                "description": "Canal rare",
                "detail": f"Ce canal n'apparaît que dans {channel_freq*100:.0f}% des transactions",
                "type": "statistical",
                "severity": "medium"
            })

        # Anomalie de catégorie
        if is_unusual_category:
            points = 28
            detail = f"Catégorie de marchand inhabituelle (habituelle : {usual_category})" if usual_category != 'Inconnue' else "Catégorie de marchand inhabituelle"
            contributions.append({
                "factor": "PATTERN",
                "impact_points": points,
                "description": "Catégorie inhabituelle",
                "detail": detail,
                "type": "statistical",
                "severity": "medium"
            })
        elif category_freq < thresholds["frequency_low"] and category_freq > 0 and total_tx > 3:
            points = int(15 + (1 - category_freq) * 15)
            contributions.append({
                "factor": "PATTERN",
                "impact_points": points,
                "description": "Catégorie rare",
                "detail": f"Cette catégorie n'apparaît que dans {category_freq*100:.0f}% des transactions",
                "type": "statistical",
                "severity": "medium"
            })

        # Anomalie horaire
        tx_time = transaction.get('TX_Time', transaction.get('transaction_date', None))
        if tx_time and total_tx > 0:
            try:
                if isinstance(tx_time, str):
                    tx_time = pd.to_datetime(tx_time)
                hour = tx_time.hour
                is_late = hour >= thresholds["late_hour_start"] or hour < thresholds["late_hour_end"]
                if is_late:
                    time_desc = f"{hour:02d}:00 (tôt le matin)" if hour < 6 else f"{hour:02d}:00 (tard dans la nuit)"
                    contributions.append({
                        "factor": "TIME_ANOMALY",
                        "impact_points": 20,
                        "description": "Horaire de transaction inhabituel",
                        "detail": f"Transaction à {time_desc} - heure atypique",
                        "type": "statistical",
                        "severity": "medium"
                    })
            except Exception:
                pass

        # Score de risque composite
        if risk_score > thresholds["risk_score_high"]:
            is_extreme = risk_score > thresholds["risk_score_extreme"]
            points = min(50, int(risk_score * 1.5))
            contributions.append({
                "factor": "RISK_SCORE",
                "impact_points": points,
                "description": f"Score de risque comportemental - {'extrême' if is_extreme else 'élevé'}",
                "detail": f"Un score de risque composite de {risk_score:.0f} indique un motif de comportement suspect",
                "type": "statistical",
                "severity": "high" if is_extreme else "medium"
            })

        # Nouveau montant maximum
        if is_new_max and total_tx > 0:
            points = int(15 + (amount / (avg_amount + 1)) * 5)
            contributions.append({
                "factor": "AMOUNT_ANOMALY",
                "impact_points": points,
                "description": "Nouveau montant maximum",
                "detail": f"Cette transaction établit un nouveau record personnel ({amount:.0f})",
                "type": "statistical",
                "severity": "medium"
            })

        # Augmentation des contributions par le score de fraude
        for c in contributions:
            boost = 0.6 + fraud_score * 0.4
            c["impact_points"] = int(c["impact_points"] * boost)

        contributions.sort(key=lambda x: x["impact_points"], reverse=True)
        return contributions

    def _build_contributions_from_shap(self, shap_contribs: Dict[str, float], fraud_score: float) -> List[Dict]:
        """Convertit les contributions SHAP en format basé sur les facteurs."""
        factor_map = self._infer_factor_map()
        factor_scores = {}
        for feature, impact in shap_contribs.items():
            factor = self._map_feature_to_factor(feature, factor_map)
            if factor:
                factor_scores[factor] = factor_scores.get(factor, 0) + impact
        if not factor_scores:
            return []
        max_impact = max(factor_scores.values())
        contributions = []
        for factor, impact in factor_scores.items():
            scaled = int((impact / max_impact) * 100 * (0.5 + fraud_score * 0.5))
            contributions.append({
                "factor": factor,
                "impact_points": scaled,
                "description": self._get_factor_description(factor),
                "detail": f"Le modèle a identifié {self._get_factor_description_fr(factor).lower()} comme facteur clé contributif",
                "type": "shap",
                "severity": "high" if scaled > 50 else "medium"
            })
        contributions.sort(key=lambda x: x["impact_points"], reverse=True)
        return contributions

    def _merge_contributions(self, contributions: List[Dict]) -> List[Dict]:
        """Fusionne les contributions ayant le même facteur."""
        merged = {}
        for c in contributions:
            factor = c["factor"]
            if factor not in merged:
                merged[factor] = c.copy()
            else:
                merged[factor]["impact_points"] += c["impact_points"]
                if len(c.get("detail", "")) > len(merged[factor].get("detail", "")):
                    merged[factor]["detail"] = c["detail"]
                if "type" in c and c["type"] not in merged[factor].get("types", []):
                    merged[factor].setdefault("types", []).append(c["type"])
        for factor, data in merged.items():
            if "types" in data:
                data["type"] = ",".join(data["types"])
                del data["types"]
        return list(merged.values())

    def _build_response(self, contributions: List[Dict], fraud_score: float,
                        transaction: Dict, behavior: Dict, source: str) -> Dict:
        """Construit la réponse finale."""
        if not contributions:
            contributions = [{
                "factor": "BEHAVIORAL",
                "impact_points": int(fraud_score * 50),
                "description": "Analyse comportementale",
                "detail": "Transaction analysée sans anomalies spécifiques détectées",
                "type": "fallback",
                "severity": "low"
            }]
        
        primary_factor = contributions[0]["factor"]
        risk_level = self._get_risk_level(fraud_score)
        total_tx = self._safe_int(behavior.get('client_total_transactions', 0))
        amount = self._safe_float(transaction.get('TX_Amount', transaction.get('amount', 0)))
        
        if total_tx == 0:
            summary = f"{risk_level} : Première transaction - aucun historique disponible"
        elif total_tx == 1:
            summary = f"{risk_level} : Historique limité - seulement {total_tx} transaction antérieure"
        else:
            summary = f"{risk_level} : {contributions[0]['description']}"
        
        narrative = self._generate_intelligent_narrative(primary_factor, contributions, transaction, behavior, fraud_score)
        avg_amount = self._safe_float(behavior.get('client_AVG_TX_Amount', 0))
        risk_score = self._safe_float(behavior.get('client_risk_score', 0))
        
        # Détermination du type d'alerte : forcer NO_HISTORY pour les premières transactions
        if total_tx == 0:
            alert_type = "NO_HISTORY"
        else:
            alert_type = self._map_factor_to_alert_type(primary_factor)
        
        fraud_reason_json = {
            "risk_level": risk_level,
            "fraud_score": round(fraud_score, 4),
            "primary_factor": primary_factor,
            "primary_factor_display": self.config["factor_display"].get(primary_factor.lower(), primary_factor),
            "summary": summary,
            "narrative": narrative,
            "client_context": {
                "total_transactions": total_tx,
                "has_history": total_tx > 0,
                "avg_amount": round(avg_amount, 2),
                "risk_score": round(risk_score, 1),
                "is_established": total_tx > 5
            },
            "contributions": self._format_contributions(contributions[:5]),
            "total_risk_points": sum(c.get("impact_points", 0) for c in contributions),
            "analysis_source": source,
            "explainers_used": {
                "shap": self.explainer_type if self.explainer else None,
                "deep_learning": self.dl_explainer is not None,
                "statistical": True
            }
        }
        
        return {'alert_type': alert_type, 'fraud_reason_json': fraud_reason_json}

    def _generate_intelligent_narrative(self, primary_factor: str, contributions: List[Dict],
                                         transaction: Dict, behavior: Dict, fraud_score: float) -> str:
        """Génère un récit détaillé et contextuel."""
        total_tx = self._safe_int(behavior.get('client_total_transactions', 0))
        amount = self._safe_float(transaction.get('TX_Amount', transaction.get('amount', 0)))
        avg_amount = self._safe_float(behavior.get('client_AVG_TX_Amount', 0))
        narratives = []
        
        if total_tx == 0:
            narratives.append(f"Le client n'a aucun historique de transaction. Première transaction de {amount:.0f}.")
        elif total_tx == 1:
            narratives.append(f"Le client a un historique limité avec seulement {total_tx} transaction antérieure.")
        else:
            narratives.append(f"Le client a {total_tx} transactions historiques avec une moyenne de {avg_amount:.0f}.")
        
        primary_contrib = contributions[0] if contributions else {}
        factor = primary_contrib.get('factor', '')
        
        if factor == 'NO_HISTORY':
            narratives.append("Première transaction - risque élevé en raison de l'absence de profil comportemental.")
        elif factor == 'LIMITED_HISTORY':
            narratives.append("Historique de transactions insuffisant pour une détection fiable des motifs.")
        elif factor == 'AMOUNT_ANOMALY':
            if avg_amount > 0:
                ratio = amount / avg_amount
                if ratio > 2:
                    narratives.append(f"Montant de la transaction {ratio:.1f} fois supérieur à la moyenne ({avg_amount:.0f}).")
                else:
                    narratives.append("Le montant de la transaction s'écarte significativement du motif normal.")
        elif factor == 'VELOCITY':
            time_since = behavior.get('client_time_since_last_tx', 0)
            narratives.append(f"La transaction a eu lieu seulement {time_since:.0f} secondes après la précédente.")
        elif factor == 'LOCATION_ANOMALY':
            usual = behavior.get('client_usual_region', 'localisation habituelle')
            narratives.append(f"Transaction depuis une localisation inhabituelle (généralement depuis {usual}).")
        elif factor == 'PATTERN':
            if behavior.get('client_is_unusual_channel', False):
                usual = behavior.get('client_usual_channel', 'canal habituel')
                narratives.append(f"Canal de transaction inhabituel (habituellement {usual}).")
            if behavior.get('client_is_unusual_category', False):
                usual = behavior.get('client_usual_category', 'catégorie habituelle')
                narratives.append(f"Catégorie de marchand inhabituelle (habituellement {usual}).")
        elif factor == 'RISK_SCORE':
            risk = behavior.get('client_risk_score', 0)
            narratives.append(f"Le score de risque comportemental de {risk:.0f} indique un motif suspect.")
        elif factor == 'DL_ANOMALY':
            narratives.append("Le réseau neuronal a détecté un motif de transaction anormal.")
        
        if fraud_score > 0.8:
            narratives.append(f"Détection de fraude avec haute confiance (score : {fraud_score:.1%}).")
        elif fraud_score > 0.6:
            narratives.append(f"Détection de fraude avec confiance modérée (score : {fraud_score:.1%}).")
        
        return " ".join(narratives)

    def _clean_feature_name(self, feature: str) -> str:
        if '__' in feature:
            feature = feature.split('__')[-1]
        return re.split(r'_[0-9]+$', feature)[0]

    def _infer_factor_map(self) -> Dict[str, str]:
        patterns = {
            r'amount|z_score|deviation|ratio|max|min': 'AMOUNT_ANOMALY',
            r'time_since|velocity|gap|hours': 'VELOCITY',
            r'region|postal|location': 'LOCATION_ANOMALY',
            r'channel|category|pattern|unusual': 'PATTERN',
            r'history|total_tx|new|first': 'NO_HISTORY',
            r'risk_score': 'RISK_SCORE'
        }
        mapping = {}
        for name in self.feature_names:
            clean = self._clean_feature_name(name).lower()
            for pattern, factor in patterns.items():
                if re.search(pattern, clean):
                    mapping[name] = factor
                    break
            else:
                mapping[name] = 'MODEL'
        return mapping

    def _map_feature_to_factor(self, feature: str, factor_map: Dict = None) -> str:
        if factor_map is None:
            factor_map = self._infer_factor_map()
        return factor_map.get(feature, 'MODEL')

    def _map_factor_to_alert_type(self, factor: str) -> str:
        mapping = {
            "AMOUNT_ANOMALY": "AMOUNT_ANOMALY",
            "VELOCITY": "VELOCITY",
            "LOCATION_ANOMALY": "LOCATION_ANOMALY",
            "PATTERN": "PATTERN",
            "NO_HISTORY": "NO_HISTORY",
            "LIMITED_HISTORY": "BEHAVIORAL",
            "RISK_SCORE": "RISK_SCORE",
            "TIME_ANOMALY": "TIME_ANOMALY",
            "AMOUNT_RATIO": "AMOUNT_ANOMALY",
            "DL_ANOMALY": "DEEP_LEARNING"
        }
        return mapping.get(factor, "BEHAVIORAL")

    def _get_risk_level(self, fraud_score: float) -> str:
        t = self.threshold
        if fraud_score >= t + (1 - t) * 0.6:
            return "CRITIQUE"
        if fraud_score >= t:
            return "ÉLEVÉ"
        if fraud_score >= t * 0.6:
            return "MOYEN"
        if fraud_score >= t * 0.3:
            return "FAIBLE"
        return "TRÈS FAIBLE"

    def _get_factor_description(self, factor: str) -> str:
        desc_map = {
            "AMOUNT_ANOMALY": "Le modèle a détecté une anomalie de montant",
            "VELOCITY": "Le modèle a détecté une vélocité anormale",
            "LOCATION_ANOMALY": "Le modèle a détecté une localisation inhabituelle",
            "PATTERN": "Le modèle a détecté un motif inhabituel",
            "NO_HISTORY": "Aucun historique comportemental disponible",
            "RISK_SCORE": "Score de risque comportemental élevé",
            "TIME_ANOMALY": "Horaire de transaction inhabituel"
        }
        return desc_map.get(factor, "Contribution significative du modèle")

    def _get_factor_description_fr(self, factor: str) -> str:
        """Version en français plus courte pour insertion dans les détails."""
        desc_map = {
            "AMOUNT_ANOMALY": "l'anomalie de montant",
            "VELOCITY": "la vélocité anormale",
            "LOCATION_ANOMALY": "la localisation inhabituelle",
            "PATTERN": "le motif inhabituel",
            "NO_HISTORY": "l'absence d'historique",
            "RISK_SCORE": "le score de risque élevé",
            "TIME_ANOMALY": "l'horaire inhabituel"
        }
        return desc_map.get(factor, "le facteur contributif")

    def _format_contributions(self, contributions: List[Dict]) -> List[Dict]:
        factor_display = self.config["factor_display"]
        formatted = []
        for c in contributions:
            factor = c["factor"]
            display = factor_display.get(factor.lower(), factor)
            formatted.append({
                "factor": factor,
                "factor_display": display,
                "description": c.get("description", self._get_factor_description(factor)),
                "detail": c.get("detail", c.get("description", "")),
                "impact_points": int(c.get("impact_points", 0)),
                "type": c.get("type", "model"),
                "severity": c.get("severity", "medium")
            })
        return formatted

    @staticmethod
    def _safe_float(value: Any, default: float = 0.0) -> float:
        if value is None:
            return default
        try:
            return float(value)
        except (ValueError, TypeError):
            return default

    @staticmethod
    def _safe_int(value: Any, default: int = 0) -> int:
        if value is None:
            return default
        try:
            return int(value)
        except (ValueError, TypeError):
            return default