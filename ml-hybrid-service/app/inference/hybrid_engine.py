import numpy as np
import pandas as pd
from typing import Dict, Any, List, Optional
import logging
from app.models.bundles import HybridEnsembleBundle

from shared_ml.explanation import FraudExplainabilityEngine
from shared_ml.utils.postal_codes import get_resolver

logger = logging.getLogger(__name__)


def _normalize_categories_and_behavioral(df: pd.DataFrame, preprocessor) -> pd.DataFrame:
    if 'TX_Time' not in df.columns:
        df['TX_Time'] = pd.Timestamp.now()
    df['TX_Time'] = pd.to_datetime(df['TX_Time'], errors='coerce')
    df['TX_Time'] = df['TX_Time'].fillna(pd.Timestamp.now())

    behavioral_cols = [
        'client_AVG_TX_Amount', 'client_AVG_TX_PerDay', 'client_amount_deviation',
        'client_abs_amount_deviation', 'client_amount_max_ratio', 'client_amount_z_score',
        'client_is_new_max', 'client_is_new_min', 'client_total_transactions',
        'client_time_since_last_tx', 'client_hours_since_last_tx', 'client_total_days_since_first',
        'client_usual_region', 'client_is_unusual_region', 'client_region_freq',
        'client_usual_channel', 'client_is_unusual_channel', 'client_channel_freq',
        'client_usual_category', 'client_is_unusual_category', 'client_category_freq',
        'client_risk_score', 'amount_abs_diff', 'amount_ratio_raw', 'log_total_tx',
        'is_unusual_region_with_history', 'is_unusual_channel_with_history', 'is_unusual_category_with_history'
    ]
    for col in behavioral_cols:
        if col not in df.columns:
            if col in ['client_usual_region', 'client_usual_channel', 'client_usual_category']:
                df[col] = 'Unknown'
            else:
                df[col] = 0.0

    numeric_cols = df.select_dtypes(include=[np.number]).columns
    df[numeric_cols] = df[numeric_cols].astype(np.float32)
    return df


def _align_features(df: pd.DataFrame, preprocessor) -> pd.DataFrame:
    expected_input_cols = None
    if hasattr(preprocessor, 'feature_names_in_'):
        expected_input_cols = list(preprocessor.feature_names_in_)
    else:
        for step in preprocessor.named_steps.values():
            if hasattr(step, 'feature_names_in_'):
                expected_input_cols = list(step.feature_names_in_)
                break
        if expected_input_cols is None:
            expected_input_cols = list(df.columns)
            if 'TX_Time' not in expected_input_cols:
                expected_input_cols.append('TX_Time')

    for col in expected_input_cols:
        if col not in df.columns:
            if col == 'TX_Time':
                df[col] = pd.Timestamp.now()
            elif col in ['Channel', 'category', 'client_usual_region', 'client_usual_channel', 'client_usual_category']:
                df[col] = 'Unknown'
            else:
                df[col] = 0.0
    return df[expected_input_cols]


class HybridEngine:
    _explainers_cache: Dict[str, FraudExplainabilityEngine] = {}

    @staticmethod
    def predict(artifact_bundle: Dict[str, Any], features_raw: pd.DataFrame,
                include_explanation: bool = False,
                existing_behavior: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        try:
            model = artifact_bundle["model"]
            preprocessor = artifact_bundle["preprocessor"]

            features_raw = _normalize_categories_and_behavioral(features_raw, preprocessor)
            features_raw = _align_features(features_raw, preprocessor)

            X_trans = preprocessor.transform(features_raw)
            if hasattr(X_trans, 'toarray'):
                X_trans = X_trans.toarray()
            X_trans = np.asarray(X_trans, dtype=np.float32)

            if isinstance(model, HybridEnsembleBundle):
                result = HybridEngine._predict_hybrid(model, preprocessor, features_raw, X_trans)
            elif hasattr(model, "predict_proba"):
                result = HybridEngine._predict_standalone_anomaly(model, X_trans)
            else:
                raise ValueError(f"Type de modèle non supporté : {type(model)}")

            transaction_dict = features_raw.iloc[0].to_dict()
            if existing_behavior is not None:
                behavior_dict = existing_behavior
            else:
                behavior_dict = {k: v for k, v in transaction_dict.items() if k.startswith('client_')}

            result['fraud_reason'] = HybridEngine._build_rich_fraud_reason(
                artifact_bundle=artifact_bundle,
                features_raw=features_raw,
                X_trans=X_trans,
                fraud_score=result['fraud_score'],
                transaction_data=transaction_dict,
                behavior_data=behavior_dict,
                threshold=artifact_bundle.get('threshold', 0.5),
                row_id=0,
                include_shap=include_explanation
            )
            if include_explanation and 'alert_type' in result['fraud_reason']:
                result['alert_type'] = result['fraud_reason']['alert_type']
            return result
        except Exception as e:
            logger.error(f"Erreur d'inférence : {e}")
            raise

    @staticmethod
    def predict_batch(artifact_bundle: Dict[str, Any], features_raw: pd.DataFrame,
                      include_explanation: bool = False,
                      existing_behaviors: Optional[List[Optional[Dict[str, Any]]]] = None) -> List[Dict[str, Any]]:
        try:
            model = artifact_bundle["model"]
            preprocessor = artifact_bundle["preprocessor"]

            features_raw = _normalize_categories_and_behavioral(features_raw, preprocessor)
            features_raw = _align_features(features_raw, preprocessor)

            X_trans = preprocessor.transform(features_raw)
            if hasattr(X_trans, 'toarray'):
                X_trans = X_trans.toarray()
            X_trans = np.asarray(X_trans, dtype=np.float32)

            if isinstance(model, HybridEnsembleBundle):
                rf_scores = model.rf_model.predict_proba(X_trans)[:, 1]
                xgb_scores = model.xgb_model.predict_proba(X_trans)[:, 1]
                copod_raw = model.copod_model.decision_function(X_trans)
                if model.copod_min is not None and model.copod_max is not None:
                    copod_scores = (copod_raw - model.copod_min) / (model.copod_max - model.copod_min + 1e-6)
                    copod_scores = np.clip(copod_scores, 0, 1)
                else:
                    copod_scores = copod_raw

                meta_features = np.column_stack([rf_scores, xgb_scores, copod_scores])
                final_probas = model.meta_learner.predict_proba(meta_features)[:, 1]

                batch_results = []
                for i in range(len(final_probas)):
                    score = float(final_probas[i])
                    threshold = getattr(model, 'threshold', 0.5)

                    transaction_dict = features_raw.iloc[i].to_dict()
                    if existing_behaviors and i < len(existing_behaviors) and existing_behaviors[i] is not None:
                        behavior_dict = existing_behaviors[i]
                    else:
                        behavior_dict = {k: v for k, v in transaction_dict.items() if k.startswith('client_')}

                    fraud_reason = HybridEngine._build_rich_fraud_reason(
                        artifact_bundle=artifact_bundle,
                        features_raw=features_raw.iloc[i:i+1],
                        X_trans=X_trans[i:i+1],
                        fraud_score=score,
                        transaction_data=transaction_dict,
                        behavior_data=behavior_dict,
                        threshold=threshold,
                        row_id=0,
                        include_shap=include_explanation and i < 5
                    )

                    # Construction des signaux comportementaux avec seconds_since_last_tx
                    signals = {
                        "client_usual_region": behavior_dict.get('client_usual_region', 'Unknown'),
                        "client_region_freq": behavior_dict.get('client_region_freq', 0.0),
                        "client_is_unusual_region": behavior_dict.get('client_is_unusual_region', False),
                        "client_usual_channel": behavior_dict.get('client_usual_channel', 'Unknown'),
                        "client_channel_freq": behavior_dict.get('client_channel_freq', 0.0),
                        "client_is_unusual_channel": behavior_dict.get('client_is_unusual_channel', False),
                        "client_usual_category": behavior_dict.get('client_usual_category', 'Unknown'),
                        "client_category_freq": behavior_dict.get('client_category_freq', 0.0),
                        "client_is_unusual_category": behavior_dict.get('client_is_unusual_category', False),
                        "seconds_since_last_tx": behavior_dict.get('client_time_since_last_tx', 0)
                    }

                    batch_results.append({
                        "fraud_score": score,
                        "risk_level": HybridEngine._compute_risk_level(score, threshold),
                        "alert_type": fraud_reason.get('alert_type', 'HYBRID_ENSEMBLE'),
                        "supervised_score": float((rf_scores[i] + xgb_scores[i]) / 2),
                        "anomaly_score": float(copod_scores[i]),
                        "behavioral_signals": signals,
                        "fraud_reason": fraud_reason
                    })
                return batch_results

            elif hasattr(model, "predict_proba"):
                scores = model.predict_proba(X_trans)
                if scores.ndim == 2:
                    scores = scores[:, 1]
                threshold = getattr(model, 'threshold_', 0.5)
                all_results = []
                for i, score in enumerate(scores):
                    transaction_dict = features_raw.iloc[i].to_dict()
                    if existing_behaviors and i < len(existing_behaviors) and existing_behaviors[i] is not None:
                        behavior_dict = existing_behaviors[i]
                    else:
                        behavior_dict = {k: v for k, v in transaction_dict.items() if k.startswith('client_')}

                    fraud_reason = HybridEngine._build_rich_fraud_reason(
                        artifact_bundle=artifact_bundle,
                        features_raw=features_raw.iloc[i:i+1],
                        X_trans=X_trans[i:i+1],
                        fraud_score=float(score),
                        transaction_data=transaction_dict,
                        behavior_data=behavior_dict,
                        threshold=threshold,
                        row_id=0,
                        include_shap=include_explanation and i < 5
                    )
                    signals = {
                        "client_usual_region": behavior_dict.get('client_usual_region', 'Unknown'),
                        "client_region_freq": behavior_dict.get('client_region_freq', 0.0),
                        "client_is_unusual_region": behavior_dict.get('client_is_unusual_region', False),
                        "client_usual_channel": behavior_dict.get('client_usual_channel', 'Unknown'),
                        "client_channel_freq": behavior_dict.get('client_channel_freq', 0.0),
                        "client_is_unusual_channel": behavior_dict.get('client_is_unusual_channel', False),
                        "client_usual_category": behavior_dict.get('client_usual_category', 'Unknown'),
                        "client_category_freq": behavior_dict.get('client_category_freq', 0.0),
                        "client_is_unusual_category": behavior_dict.get('client_is_unusual_category', False),
                        "seconds_since_last_tx": behavior_dict.get('client_time_since_last_tx', 0)
                    }
                    all_results.append({
                        "fraud_score": float(score),
                        "risk_level": HybridEngine._compute_risk_level(float(score), threshold),
                        "alert_type": fraud_reason.get('alert_type', getattr(model, 'name', 'ANOMALY').upper()),
                        "supervised_score": 0.0,
                        "anomaly_score": float(score),
                        "fraud_reason": fraud_reason,
                        "behavioral_signals": signals
                    })
                return all_results
            else:
                raise ValueError(f"Type de modèle non supporté : {type(model)}")
        except Exception as e:
            logger.error(f"Erreur d'inférence batch : {e}")
            raise

    @staticmethod
    def _build_rich_fraud_reason(
        artifact_bundle: Dict[str, Any],
        features_raw: pd.DataFrame,
        X_trans: np.ndarray,
        fraud_score: float,
        transaction_data: Dict[str, Any],
        behavior_data: Dict[str, Any],
        threshold: float,
        row_id: int = 0,
        include_shap: bool = False
    ) -> Dict[str, Any]:
        """Construit un fraud_reason riche (règles métier + SHAP optionnel)."""
        risk_level = HybridEngine._compute_risk_level(fraud_score, threshold)
        amount = float(transaction_data.get('TX_Amount', transaction_data.get('amount', 0)))
        total_tx = int(behavior_data.get('client_total_transactions', 0))
        avg_amount = float(behavior_data.get('client_AVG_TX_Amount', 0))
        has_history = total_tx > 0
        is_first = not has_history

        # Contexte client
        client_context = {
            "avg_amount": avg_amount,
            "risk_score": float(behavior_data.get('client_risk_score', 0)),
            "has_history": has_history,
            "is_established": int(behavior_data.get('client_total_days_since_first', 0)) > 30,
            "total_transactions": total_tx
        }

        # Contributions par règles métier
        contributions = []
        total_risk_points = 0

        if is_first:
            contributions.append({
                "type": "statistical",
                "detail": "This is the client's first transaction. No behavioral profile exists.",
                "factor": "NO_HISTORY",
                "severity": "high",
                "description": "First transaction - No history",
                "impact_points": 24,
                "factor_display": "NO_HISTORY"
            })
            total_risk_points += 24

        if has_history and amount > 3 * avg_amount and avg_amount > 0:
            contributions.append({
                "type": "statistical",
                "detail": f"Amount {amount:.2f} exceeds usual average {avg_amount:.2f} by factor {amount/avg_amount:.1f}",
                "factor": "AMOUNT_OUTLIER",
                "severity": "high",
                "description": "Amount far above average",
                "impact_points": 30,
                "factor_display": "AMOUNT_OUTLIER"
            })
            total_risk_points += 30

        current_region = transaction_data.get('Customer_Region', 'Unknown')
        usual_region = behavior_data.get('client_usual_region', 'Unknown')
        if current_region != 'Unknown' and usual_region != 'Unknown' and current_region != usual_region:
            contributions.append({
                "type": "statistical",
                "detail": f"Transaction from {current_region} while client usually operates in {usual_region}",
                "factor": "UNUSUAL_REGION",
                "severity": "medium",
                "description": "Unusual geographic location",
                "impact_points": 15,
                "factor_display": "UNUSUAL_REGION"
            })
            total_risk_points += 15

        current_channel = transaction_data.get('Channel', 'Unknown')
        usual_channel = behavior_data.get('client_usual_channel', 'Unknown')
        if current_channel != 'Unknown' and usual_channel != 'Unknown' and current_channel != usual_channel:
            contributions.append({
                "type": "statistical",
                "detail": f"Transaction via {current_channel} while client usually uses {usual_channel}",
                "factor": "UNUSUAL_CHANNEL",
                "severity": "medium",
                "description": "Unusual transaction channel",
                "impact_points": 10,
                "factor_display": "UNUSUAL_CHANNEL"
            })
            total_risk_points += 10

        # SHAP (si demandé et disponible)
        shap_used = False
        if include_shap:
            model = artifact_bundle.get("model")
            if isinstance(model, HybridEnsembleBundle):
                xgb_model = model.get_xgb_classifier()
                if xgb_model is not None:
                    model_uuid = artifact_bundle.get('model_uuid', getattr(model, 'model_uuid', 'default'))
                    explainer = HybridEngine._get_explainer(
                        model_uuid, xgb_model, artifact_bundle['preprocessor'],
                        model.background_data, model.feature_names, threshold
                    )
                    if explainer:
                        try:
                            tx_data_shap = {
                                'TX_Amount': amount,
                                'TX_Time': transaction_data.get('TX_Time'),
                                'Channel': current_channel,
                                'category': transaction_data.get('category', 'Unknown'),
                                'Customer_Postal_Code': transaction_data.get('Customer_Postal_Code', '')
                            }
                            explanation = explainer.explain_fraud(
                                features=X_trans[row_id],
                                fraud_score=fraud_score,
                                transaction_data=tx_data_shap,
                                behavior_data=behavior_data
                            )
                            fraud_reason_json = explanation['fraud_reason_json']
                            fraud_reason_json['alert_type'] = explanation.get('alert_type', 'BEHAVIORAL')
                            fraud_reason_json['total_risk_points'] = total_risk_points
                            return fraud_reason_json
                        except Exception as e:
                            logger.warning(f"SHAP explanation failed: {e}, fallback to rule-based")

        # Construction finale (règles uniquement)
        if is_first:
            summary = f"{risk_level}: First transaction - no history available"
            narrative = f"Client has no transaction history. First transaction of {amount:.2f}."
        elif contributions:
            main_factor = contributions[0]['factor_display']
            summary = f"{risk_level}: {main_factor} detected (score {fraud_score:.3f})"
            narrative = f"Transaction amount {amount:.2f}. " + contributions[0]['detail']
        else:
            summary = f"{risk_level}: Normal behavior, score {fraud_score:.3f}"
            narrative = f"Transaction amount {amount:.2f} within usual patterns."

        primary_factor = contributions[0]['factor'] if contributions else "NORMAL"
        primary_factor_display = contributions[0]['factor_display'] if contributions else "NORMAL"

        return {
            "summary": summary,
            "narrative": narrative,
            "risk_level": risk_level,
            "fraud_score": round(fraud_score, 4),
            "contributions": contributions,
            "client_context": client_context,
            "primary_factor": primary_factor,
            "analysis_source": "statistical_rules" + ("+shap" if shap_used else ""),
            "explainers_used": {
                "shap": shap_used,
                "statistical": True,
                "deep_learning": False
            },
            "total_risk_points": total_risk_points,
            "primary_factor_display": primary_factor_display,
            "alert_type": "RULE_BASED"
        }

    @staticmethod
    def _get_explainer(model_uuid: str, xgb_model, preprocessor,
                       background_data: Optional[np.ndarray],
                       feature_names: Optional[List[str]],
                       threshold: float) -> Optional[FraudExplainabilityEngine]:
        if model_uuid in HybridEngine._explainers_cache:
            return HybridEngine._explainers_cache[model_uuid]
        if background_data is None or feature_names is None:
            return None
        try:
            explainer = FraudExplainabilityEngine(
                model=xgb_model,
                feature_names=feature_names,
                background_data=background_data,
                threshold=threshold,
                config_path=None,
                dl_model_path=None,
                dl_input_dim=None
            )
            HybridEngine._explainers_cache[model_uuid] = explainer
            logger.info(f"Explainability engine initialized for {model_uuid}")
            return explainer
        except Exception as e:
            logger.error(f"Failed to init explainer: {e}")
            return None

    @staticmethod
    def _compute_risk_level(score: float, threshold: float) -> str:
        if score >= 0.9:
            return "CRITICAL"
        if score >= threshold:
            return "HIGH"
        if score >= threshold / 2:
            return "MEDIUM"
        return "LOW"

    @staticmethod
    def _predict_hybrid(bundle: HybridEnsembleBundle, preprocessor: Any,
                        features_raw: pd.DataFrame, X_trans: np.ndarray) -> Dict[str, Any]:
        rf_score = bundle.rf_model.predict_proba(X_trans)[:, 1]
        xgb_score = bundle.xgb_model.predict_proba(X_trans)[:, 1]
        copod_raw = bundle.copod_model.decision_function(X_trans)
        if bundle.copod_min is not None and bundle.copod_max is not None:
            copod_score = (copod_raw - bundle.copod_min) / (bundle.copod_max - bundle.copod_min + 1e-6)
            copod_score = np.clip(copod_score, 0, 1)
        else:
            copod_score = copod_raw

        meta_features = np.column_stack([rf_score, xgb_score, copod_score])
        final_proba = bundle.meta_learner.predict_proba(meta_features)[:, 1]
        score = float(final_proba[0])
        threshold = getattr(bundle, 'threshold', 0.5)

        return {
            "fraud_score": score,
            "risk_level": HybridEngine._compute_risk_level(score, threshold),
            "alert_type": "HYBRID_ENSEMBLE",
            "supervised_score": float((rf_score[0] + xgb_score[0]) / 2),
            "anomaly_score": float(copod_score[0]),
            "behavioral_signals": {
                "is_outlier": bool(copod_score[0] > 0.8),
                "consensus": bool(rf_score[0] > threshold and xgb_score[0] > threshold)
            }
        }

    @staticmethod
    def _predict_standalone_anomaly(model: Any, X_trans: np.ndarray) -> Dict[str, Any]:
        scores = model.predict_proba(X_trans)
        if scores.ndim == 2:
            scores = scores[:, 1]
        score = float(scores[0]) if len(scores) > 0 else 0.0
        threshold = getattr(model, 'threshold_', 0.5)

        return {
            "fraud_score": score,
            "risk_level": HybridEngine._compute_risk_level(score, threshold),
            "alert_type": getattr(model, 'name', 'ANOMALY').upper(),
            "supervised_score": 0.0,
            "anomaly_score": score,
            "behavioral_signals": {"deviation_rank": score}
        }