"""
Hyper-intelligent Explainability Engine v5.1
- Auto-detects model type and selects optimal explainer
- Uses Integrated Gradients for neural networks (via Captum)
- Falls back to KernelExplainer for any other model type
- Overrides alert_type to "NO_HISTORY" for first-time transactions
- Graceful degradation – never crashes
"""

import re
import json
import sys
import numpy as np
import pandas as pd
from typing import Dict, List, Optional, Any, Tuple
from datetime import datetime

import shap

# Deep learning imports (optional but recommended)
try:
    import torch
    import torch.nn as nn
    from captum.attr import IntegratedGradients, DeepLift, GradientShap
    TORCH_AVAILABLE = True
except ImportError:
    TORCH_AVAILABLE = False

__all__ = ['FraudExplainabilityEngine']


class DeepLearningExplainer:
    """
    Advanced deep learning explainer using Captum.
    Supports Integrated Gradients, DeepLIFT, and GradientSHAP.
    """

    def __init__(self, model_path: str, input_dim: int, device: str = "cpu", method: str = "integrated_gradients"):
        if not TORCH_AVAILABLE:
            raise ImportError("PyTorch and Captum are required for deep learning explainability")
        
        self.device = torch.device(device)
        self.input_dim = input_dim
        self.method = method
        
        # Load model
        try:
            self.model = torch.jit.load(model_path, map_location=self.device)
        except:
            # Try loading as state dict
            self.model = self._load_state_dict_model(model_path, input_dim)
        
        self.model.eval()
        
        # Initialize attribution method
        if method == "integrated_gradients":
            self.attributor = IntegratedGradients(self.model)
        elif method == "deep_lift":
            self.attributor = DeepLift(self.model)
        elif method == "gradient_shap":
            self.attributor = GradientShap(self.model)
        else:
            self.attributor = IntegratedGradients(self.model)
    
    def _load_state_dict_model(self, model_path: str, input_dim: int) -> nn.Module:
        """Load a PyTorch model from state dict."""
        # Simple MLP as default architecture
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
        """Forward pass: returns anomaly probability."""
        tensor = torch.from_numpy(features).float().to(self.device).unsqueeze(0)
        with torch.no_grad():
            output = self.model(tensor)
        
        if isinstance(output, torch.Tensor):
            if output.numel() == 1:
                return float(torch.sigmoid(output).item())
            return float(output.mean().item())
        return float(output)
    
    def get_feature_attributions(self, features: np.ndarray) -> np.ndarray:
        """Get feature attributions using Captum."""
        tensor = torch.from_numpy(features).float().to(self.device).unsqueeze(0)
        baselines = torch.zeros_like(tensor)
        
        if self.method == "gradient_shap":
            # GradientSHAP needs multiple baselines
            baselines = torch.randn(10, *tensor.shape[1:]).to(self.device)
            attributions = self.attributor.attribute(tensor, baselines, target=0)
        else:
            attributions = self.attributor.attribute(tensor, baselines, target=0)
        
        return attributions.squeeze().cpu().detach().numpy()


class FraudExplainabilityEngine:
    """
    Universal explainer that automatically selects the best method for any model.
    - Tree-based models: SHAP TreeExplainer
    - Neural networks: Captum Integrated Gradients / DeepLIFT
    - Other models: SHAP KernelExplainer
    - Always falls back to statistical analysis if all else fails
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
        
        # Detect model type and initialize appropriate explainer
        model_type = self._detect_model_type(model)
        print(f"Detected model type: {model_type}", file=sys.stderr)
        
        # Initialize SHAP explainer for non-DL models
        if model_type in ['tree', 'linear', 'unknown']:
            self._init_shap_explainer(model, background_data)
        
        # Initialize deep learning explainer (if provided)
        if TORCH_AVAILABLE and dl_model_path and dl_input_dim:
            try:
                self.dl_explainer = DeepLearningExplainer(
                    dl_model_path, dl_input_dim, device="cpu", method=dl_method
                )
                print(f"Deep learning explainer initialized (method: {dl_method})", file=sys.stderr)
            except Exception as e:
                print(f"DL explainer initialization failed: {e}", file=sys.stderr)

    def _detect_model_type(self, model) -> str:
        """Detect model type with high accuracy."""
        model_str = str(type(model)).lower()
        
        # Tree-based models
        if any(x in model_str for x in ['lightgbm', 'lgbm', 'xgboost', 'xgb', 'catboost', 
                                         'randomforest', 'random_forest', 'gradientboosting']):
            return 'tree'
        
        # Linear models
        if any(x in model_str for x in ['linear', 'logistic', 'ridge', 'lasso', 'svc', 'svm']):
            return 'linear'
        
        # Neural networks (scikit-learn MLP)
        if 'mlp' in model_str or 'neural' in model_str:
            return 'neural'
        
        # Try to detect by methods
        if hasattr(model, 'get_booster'):
            return 'tree'
        if hasattr(model, 'trees_'):
            return 'tree'
        
        return 'unknown'

    def _init_shap_explainer(self, model, background_data: np.ndarray):
        """Initialize SHAP explainer with intelligent fallback chain."""
        
        # Strategy 1: TreeExplainer with interventional (works with small backgrounds)
        if background_data is not None and len(background_data) > 0:
            try:
                self.explainer = shap.TreeExplainer(
                    model, background_data, feature_perturbation="interventional"
                )
                self.explainer_type = "TreeExplainer(interventional)"
                print(f"SHAP {self.explainer_type} initialized", file=sys.stderr)
                return
            except Exception as e:
                print(f"TreeExplainer(interventional) failed: {e}", file=sys.stderr)
        
        # Strategy 2: TreeExplainer without background (always works)
        try:
            self.explainer = shap.TreeExplainer(model)
            self.explainer_type = "TreeExplainer(no_background)"
            print(f"SHAP {self.explainer_type} initialized", file=sys.stderr)
            return
        except Exception as e:
            print(f"TreeExplainer(no_background) failed: {e}", file=sys.stderr)
        
        # Strategy 3: KernelExplainer (works with any model, slower but universal)
        if background_data is not None and len(background_data) >= 10:
            try:
                bg_sample = background_data[:min(100, len(background_data))]
                
                # Define prediction function
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
                    raise ValueError("Model has no prediction method")
                
                self.explainer = shap.KernelExplainer(predict_fn, bg_sample)
                self.explainer_type = "KernelExplainer"
                print(f"SHAP {self.explainer_type} initialized", file=sys.stderr)
                return
            except Exception as e:
                print(f"KernelExplainer failed: {e}", file=sys.stderr)
        
        print("WARNING: No SHAP explainer could be initialized. Using statistical analysis only.", file=sys.stderr)

    def _load_config(self, config_path: Optional[str]) -> Dict:
        default_config = {
            "factor_display": {
                "amount": "Montant",
                "velocity": "Frequence",
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
                print(f"Warning: Could not load config: {e}", file=sys.stderr)
        return default_config

    def explain_fraud(self, features: np.ndarray, fraud_score: float,
                      transaction_data: Dict, behavior_data: Dict) -> Dict:
        """Generate intelligent explanation using the best available method."""
        contributions = []

        # 1. SHAP contributions (if available)
        if self.explainer:
            shap_contribs = self._get_shap_explanations(features)
            if shap_contribs:
                contributions.extend(self._build_contributions_from_shap(shap_contribs, fraud_score))

        # 2. Deep learning contributions (if available)
        if self.dl_explainer:
            dl_contribs = self._get_dl_contributions(features, fraud_score)
            if dl_contribs:
                contributions.extend(dl_contribs)

        # 3. Statistical rule‑based contributions (always computed as fallback)
        stat_contribs = self._intelligent_behavior_analysis(behavior_data, transaction_data, fraud_score)
        contributions.extend(stat_contribs)

        # Merge and sort
        contributions = self._merge_contributions(contributions)
        contributions.sort(key=lambda x: x["impact_points"], reverse=True)

        # Determine source for response
        if self.dl_explainer and self.explainer:
            source = "hybrid(shap+dl+statistical)"
        elif self.dl_explainer:
            source = "deep_learning+statistical"
        elif self.explainer:
            source = f"shap({self.explainer_type})+statistical"
        else:
            source = "statistical_only"

        return self._build_response(contributions, fraud_score, transaction_data, behavior_data, source=source)

    def _get_shap_explanations(self, features: np.ndarray) -> Dict[str, float]:
        """Extract SHAP values with error handling."""
        if not self.explainer:
            return {}
        
        try:
            shap_values = self.explainer.shap_values(features.reshape(1, -1))
            
            # Handle different output formats
            if isinstance(shap_values, list):
                # Binary classification: take positive class
                shap_values = shap_values[1] if len(shap_values) > 1 else shap_values[0]
            if isinstance(shap_values, np.ndarray) and len(shap_values.shape) > 1:
                shap_vals = shap_values[0] if shap_values.shape[0] == 1 else shap_values
            else:
                shap_vals = shap_values
            
            # Ensure 1D array
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
            print(f"SHAP extraction failed: {e}", file=sys.stderr)
            return {}

    def _get_dl_contributions(self, features: np.ndarray, fraud_score: float) -> List[Dict]:
        """Get deep learning contributions using Captum."""
        if not self.dl_explainer:
            return []
        
        try:
            dl_anomaly = self.dl_explainer.predict_anomaly_score(features)
            attributions = self.dl_explainer.get_feature_attributions(features)
            
            contributions = [{
                "factor": "DL_ANOMALY",
                "impact_points": int(dl_anomaly * 60),
                "description": "Deep learning anomaly detection",
                "detail": f"Neural network detected anomalous pattern (confidence: {dl_anomaly:.1%})",
                "type": "deep_learning",
                "severity": "high" if dl_anomaly > 0.7 else "medium"
            }]
            
            # Add top contributing features
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
                            "description": f"Neural network: {clean_name}",
                            "detail": f"Deep learning model flagged {clean_name} (importance: {abs(attr_val):.3f})",
                            "type": "deep_learning",
                            "severity": "medium"
                        })
            
            return contributions
            
        except Exception as e:
            print(f"DL attribution failed: {e}", file=sys.stderr)
            return []

    # ----------------------------------------------------------------------
    # Statistical rule‑based analysis
    # ----------------------------------------------------------------------
    def _intelligent_behavior_analysis(self, behavior: Dict, transaction: Dict, fraud_score: float) -> List[Dict]:
        """Advanced analysis using ALL available behavior signals."""
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
        usual_region = behavior.get('client_usual_region', 'Unknown')
        usual_channel = behavior.get('client_usual_channel', 'Unknown')
        usual_category = behavior.get('client_usual_category', 'Unknown')
        total_days = self._safe_float(behavior.get('client_total_days_since_first', 0))
        is_new_max = bool(behavior.get('client_is_new_max', False))

        # History analysis
        if total_tx == 0:
            contributions.append({
                "factor": "NO_HISTORY",
                "impact_points": int(25 + fraud_score * 25),
                "description": "First transaction - No history",
                "detail": "This is the client's first transaction. No behavioral profile exists.",
                "type": "statistical",
                "severity": "high"
            })
        elif total_tx == 1:
            contributions.append({
                "factor": "LIMITED_HISTORY",
                "impact_points": int(15 + fraud_score * 15),
                "description": "Limited transaction history",
                "detail": f"Only {total_tx} previous transaction. Insufficient data for reliable profiling.",
                "type": "statistical",
                "severity": "medium"
            })
        else:
            contributions.append({
                "factor": "ESTABLISHED_CLIENT",
                "impact_points": 5,
                "description": "Established client",
                "detail": f"Client with {total_tx} historical transactions over {total_days:.0f} days",
                "type": "statistical",
                "severity": "low"
            })

        # Amount anomaly
        if abs(z_score) > thresholds["z_score_anomaly"]:
            is_extreme = abs(z_score) > thresholds["z_score_extreme"]
            points = min(60, int(20 + abs(z_score) * 10))
            if avg_amount > 0:
                deviation_pct = (amount - avg_amount) / avg_amount * 100
                direction = "above" if deviation_pct > 0 else "below"
                detail = f"Amount {direction} average by {abs(deviation_pct):.0f}% (avg: {avg_amount:.0f})"
            else:
                detail = f"Amount anomaly detected (z-score={z_score:.1f})"
            contributions.append({
                "factor": "AMOUNT_ANOMALY",
                "impact_points": points,
                "description": f"Amount anomaly - {'extreme' if is_extreme else 'significant'}",
                "detail": detail,
                "type": "statistical",
                "severity": "high" if is_extreme else "medium"
            })
        elif amount_ratio > thresholds["amount_ratio_high"] and total_tx > 0:
            points = int(30 + (amount_ratio - thresholds["amount_ratio_high"]) * 10)
            contributions.append({
                "factor": "AMOUNT_RATIO",
                "impact_points": points,
                "description": "Unusually high amount",
                "detail": f"Amount is {amount_ratio:.1f}x higher than historical maximum",
                "type": "statistical",
                "severity": "high"
            })

        # Velocity anomaly
        if 0 < time_since < thresholds["velocity_seconds"] and total_tx > 0:
            is_extreme = time_since < thresholds["velocity_extreme"]
            points = int(20 + (thresholds["velocity_seconds"] - time_since) / thresholds["velocity_seconds"] * 40)
            contributions.append({
                "factor": "VELOCITY",
                "impact_points": points,
                "description": f"Transaction velocity - {'extreme' if is_extreme else 'rapid'}",
                "detail": f"Only {time_since:.0f} seconds since previous transaction",
                "type": "statistical",
                "severity": "high" if is_extreme else "medium"
            })

        # Location anomaly
        if is_unusual_region:
            points = 35
            detail = f"Transaction from unusual location (usual: {usual_region})" if usual_region != 'Unknown' else "Transaction from unusual location"
            contributions.append({
                "factor": "LOCATION_ANOMALY",
                "impact_points": points,
                "description": "Unusual location",
                "detail": detail,
                "type": "statistical",
                "severity": "high"
            })
        elif region_freq < thresholds["frequency_low"] and region_freq > 0 and total_tx > 3:
            points = int(20 + (1 - region_freq) * 20)
            contributions.append({
                "factor": "LOCATION_ANOMALY",
                "impact_points": points,
                "description": "Rare location",
                "detail": f"This location appears in only {region_freq*100:.0f}% of transactions",
                "type": "statistical",
                "severity": "medium"
            })

        # Channel anomaly
        if is_unusual_channel:
            points = 30
            detail = f"Unusual transaction channel (usual: {usual_channel})" if usual_channel != 'Unknown' else "Unusual transaction channel"
            contributions.append({
                "factor": "PATTERN",
                "impact_points": points,
                "description": "Unusual channel",
                "detail": detail,
                "type": "statistical",
                "severity": "high"
            })
        elif channel_freq < thresholds["frequency_low"] and channel_freq > 0 and total_tx > 3:
            points = int(15 + (1 - channel_freq) * 15)
            contributions.append({
                "factor": "PATTERN",
                "impact_points": points,
                "description": "Rare channel",
                "detail": f"This channel appears in only {channel_freq*100:.0f}% of transactions",
                "type": "statistical",
                "severity": "medium"
            })

        # Category anomaly
        if is_unusual_category:
            points = 28
            detail = f"Unusual merchant category (usual: {usual_category})" if usual_category != 'Unknown' else "Unusual merchant category"
            contributions.append({
                "factor": "PATTERN",
                "impact_points": points,
                "description": "Unusual category",
                "detail": detail,
                "type": "statistical",
                "severity": "medium"
            })
        elif category_freq < thresholds["frequency_low"] and category_freq > 0 and total_tx > 3:
            points = int(15 + (1 - category_freq) * 15)
            contributions.append({
                "factor": "PATTERN",
                "impact_points": points,
                "description": "Rare category",
                "detail": f"This category appears in only {category_freq*100:.0f}% of transactions",
                "type": "statistical",
                "severity": "medium"
            })

        # Time anomaly
        tx_time = transaction.get('TX_Time', transaction.get('transaction_date', None))
        if tx_time and total_tx > 0:
            try:
                if isinstance(tx_time, str):
                    tx_time = pd.to_datetime(tx_time)
                hour = tx_time.hour
                is_late = hour >= thresholds["late_hour_start"] or hour < thresholds["late_hour_end"]
                if is_late:
                    time_desc = f"{hour:02d}:00 (early morning)" if hour < 6 else f"{hour:02d}:00 (late night)"
                    contributions.append({
                        "factor": "TIME_ANOMALY",
                        "impact_points": 20,
                        "description": "Unusual transaction time",
                        "detail": f"Transaction at {time_desc} - atypical hour",
                        "type": "statistical",
                        "severity": "medium"
                    })
            except Exception:
                pass

        # Composite risk score
        if risk_score > thresholds["risk_score_high"]:
            is_extreme = risk_score > thresholds["risk_score_extreme"]
            points = min(50, int(risk_score * 1.5))
            contributions.append({
                "factor": "RISK_SCORE",
                "impact_points": points,
                "description": f"Behavioral risk score - {'extreme' if is_extreme else 'elevated'}",
                "detail": f"Composite risk score of {risk_score:.0f} indicates suspicious behavior pattern",
                "type": "statistical",
                "severity": "high" if is_extreme else "medium"
            })

        # New max amount
        if is_new_max and total_tx > 0:
            points = int(15 + (amount / (avg_amount + 1)) * 5)
            contributions.append({
                "factor": "AMOUNT_ANOMALY",
                "impact_points": points,
                "description": "New maximum amount",
                "detail": f"This transaction sets a new personal record ({amount:.0f})",
                "type": "statistical",
                "severity": "medium"
            })

        # Boost contributions by fraud_score
        for c in contributions:
            boost = 0.6 + fraud_score * 0.4
            c["impact_points"] = int(c["impact_points"] * boost)

        contributions.sort(key=lambda x: x["impact_points"], reverse=True)
        return contributions

    def _build_contributions_from_shap(self, shap_contribs: Dict[str, float], fraud_score: float) -> List[Dict]:
        """Convert SHAP contributions to factor-based format."""
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
                "detail": f"Model identified {factor.lower().replace('_', ' ')} as key contributing factor",
                "type": "shap",
                "severity": "high" if scaled > 50 else "medium"
            })
        contributions.sort(key=lambda x: x["impact_points"], reverse=True)
        return contributions

    def _merge_contributions(self, contributions: List[Dict]) -> List[Dict]:
        """Merge contributions with the same factor."""
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
        """Build the final response."""
        if not contributions:
            contributions = [{
                "factor": "BEHAVIORAL",
                "impact_points": int(fraud_score * 50),
                "description": "Behavioral analysis",
                "detail": "Transaction analyzed with no specific anomalies detected",
                "type": "fallback",
                "severity": "low"
            }]
        
        primary_factor = contributions[0]["factor"]
        risk_level = self._get_risk_level(fraud_score)
        total_tx = self._safe_int(behavior.get('client_total_transactions', 0))
        amount = self._safe_float(transaction.get('TX_Amount', transaction.get('amount', 0)))
        
        if total_tx == 0:
            summary = f"{risk_level}: First transaction - no history available"
        elif total_tx == 1:
            summary = f"{risk_level}: Limited history - only {total_tx} previous transaction"
        else:
            summary = f"{risk_level}: {contributions[0]['description']}"
        
        narrative = self._generate_intelligent_narrative(primary_factor, contributions, transaction, behavior, fraud_score)
        avg_amount = self._safe_float(behavior.get('client_AVG_TX_Amount', 0))
        risk_score = self._safe_float(behavior.get('client_risk_score', 0))
        
        # Determine alert_type: force NO_HISTORY for first-time transactions
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
        """Generate detailed, contextual narrative."""
        total_tx = self._safe_int(behavior.get('client_total_transactions', 0))
        amount = self._safe_float(transaction.get('TX_Amount', transaction.get('amount', 0)))
        avg_amount = self._safe_float(behavior.get('client_AVG_TX_Amount', 0))
        narratives = []
        
        if total_tx == 0:
            narratives.append(f"Client has no transaction history. First transaction of {amount:.0f}.")
        elif total_tx == 1:
            narratives.append(f"Client has limited history with only {total_tx} previous transaction.")
        else:
            narratives.append(f"Client has {total_tx} historical transactions averaging {avg_amount:.0f}.")
        
        primary_contrib = contributions[0] if contributions else {}
        factor = primary_contrib.get('factor', '')
        
        if factor == 'NO_HISTORY':
            narratives.append("First transaction - elevated risk due to lack of behavioral profile.")
        elif factor == 'LIMITED_HISTORY':
            narratives.append("Insufficient transaction history for reliable pattern detection.")
        elif factor == 'AMOUNT_ANOMALY':
            if avg_amount > 0:
                ratio = amount / avg_amount
                if ratio > 2:
                    narratives.append(f"Transaction amount {ratio:.1f}x higher than average ({avg_amount:.0f}).")
                else:
                    narratives.append("Transaction amount deviates significantly from normal pattern.")
        elif factor == 'VELOCITY':
            time_since = behavior.get('client_time_since_last_tx', 0)
            narratives.append(f"Transaction occurred only {time_since:.0f} seconds after previous one.")
        elif factor == 'LOCATION_ANOMALY':
            usual = behavior.get('client_usual_region', 'usual location')
            narratives.append(f"Transaction from unusual location (typically from {usual}).")
        elif factor == 'PATTERN':
            if behavior.get('client_is_unusual_channel', False):
                usual = behavior.get('client_usual_channel', 'usual channel')
                narratives.append(f"Unusual transaction channel (typically {usual}).")
            if behavior.get('client_is_unusual_category', False):
                usual = behavior.get('client_usual_category', 'usual category')
                narratives.append(f"Unusual merchant category (typically {usual}).")
        elif factor == 'RISK_SCORE':
            risk = behavior.get('client_risk_score', 0)
            narratives.append(f"Behavioral risk score of {risk:.0f} indicates suspicious pattern.")
        elif factor == 'DL_ANOMALY':
            narratives.append("Neural network detected anomalous transaction pattern.")
        
        if fraud_score > 0.8:
            narratives.append(f"High confidence fraud detection (score: {fraud_score:.1%}).")
        elif fraud_score > 0.6:
            narratives.append(f"Moderate confidence fraud detection (score: {fraud_score:.1%}).")
        
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
            "NO_HISTORY": "NO_HISTORY",       # Changed from BEHAVIORAL
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
            return "CRITICAL"
        if fraud_score >= t:
            return "HIGH"
        if fraud_score >= t * 0.6:
            return "MEDIUM"
        if fraud_score >= t * 0.3:
            return "LOW"
        return "VERY_LOW"

    def _get_factor_description(self, factor: str) -> str:
        desc_map = {
            "AMOUNT_ANOMALY": "Model detected amount anomaly",
            "VELOCITY": "Model detected abnormal velocity",
            "LOCATION_ANOMALY": "Model detected unusual location",
            "PATTERN": "Model detected unusual pattern",
            "NO_HISTORY": "No behavioral history available",
            "RISK_SCORE": "Elevated behavioral risk score",
            "TIME_ANOMALY": "Unusual transaction time"
        }
        return desc_map.get(factor, "Significant model contribution")

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