import re

import numpy as np
import pandas as pd
from typing import Dict, Any, List, Optional
import logging
from app.models.bundles import HybridEnsembleBundle

from shared_ml.explanation import FraudExplainabilityEngine
from shared_ml.utils.postal_codes import (
    ALL_REGIONS,
    process_customer_postal_transform,
    process_merchant_postal_transform,
)

logger = logging.getLogger(__name__)

_CHANNEL_ALIASES = {
    "Online": "En_Ligne",
    "ATM": "GAB",
    "POS": "TPE",
}

_CATEGORICAL_CLEAN_COLS = [
    "Channel",
    "region",
    "Customer_Region",
    "client_usual_channel",
    "client_usual_region",
]


def _clean_cat_value(value: Any, col: Optional[str] = None) -> str:
    if pd.isna(value):
        return "Unknown"

    s = str(value).strip()

    if col not in ("category", "client_usual_category"):
        if len(s) >= 2 and (
            (s[0] == "'" and s[-1] == "'") or
            (s[0] == '"' and s[-1] == '"')
        ):
            s = s[1:-1].strip()

        if len(s) >= 2 and (
            (s[0] == "'" and s[-1] == "'") or
            (s[0] == '"' and s[-1] == '"')
        ):
            s = s[1:-1].strip()

    if not s:
        s = "Unknown"

    if col in ("Channel", "client_usual_channel"):
        s = _CHANNEL_ALIASES.get(s, s)

    return s


def _normalize_categorical_values(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    for col in _CATEGORICAL_CLEAN_COLS:
        if col in df.columns:
            df[col] = df[col].apply(lambda v: _clean_cat_value(v, col))
    return df


def _extract_numeric_from_id(value):
    """Same logic as train_ecod.extract_numeric_from_id."""
    if pd.isna(value):
        return 0
    try:
        if isinstance(value, (int, float)):
            return float(value)
        s = str(value)
        if '-' in s:
            parts = s.split('-')
            if len(parts) > 1 and parts[1].replace('.', '', 1).isdigit():
                return float(parts[1])
        numbers = re.findall(r'\d+', s)
        if numbers:
            return float(numbers[0])
        return 0
    except Exception:
        return 0


def _apply_pre_pipeline_transforms(df: pd.DataFrame, preprocessor) -> pd.DataFrame:
    """
    Replicate the same pre-pipeline transformations applied during training
    (process_id_columns, postal code → region) so that the feature distribution
    seen by the ECOD model matches training.
    """
    df = df.copy()

    # 1. Convert ID columns to numeric (same as train_ecod.process_id_columns)
    for col in ['Account_Id', 'Device_Id']:
        if col in df.columns:
            df[col] = df[col].apply(_extract_numeric_from_id).astype(np.float32)

    # 2. Customer postal code → Customer_Region
    if 'Customer_Postal_Code' in df.columns:
        customer_cats = getattr(preprocessor, '_customer_region_categories_', None)
        if customer_cats is None:
            customer_cats = ALL_REGIONS
            logger.info("Using ALL_REGIONS fallback for customer region categories")
        try:
            df = process_customer_postal_transform(df, customer_cats)
        except Exception as e:
            logger.warning("Failed to apply customer postal transform: %s", e)

    # 3. Merchant postal code → Merchant_Region
    if 'Merchant_Postal_Code' in df.columns:
        merchant_cats = getattr(preprocessor, '_merchant_region_categories_', None)
        if merchant_cats is not None:
            try:
                df = process_merchant_postal_transform(df, merchant_cats)
            except Exception as e:
                logger.warning("Failed to apply merchant postal transform: %s", e)

    return df


def _normalize_categories_and_behavioral(df: pd.DataFrame, preprocessor) -> pd.DataFrame:
    df = df.copy()

    if 'TX_Time' not in df.columns:
        df['TX_Time'] = pd.Timestamp.now()
    df['TX_Time'] = pd.to_datetime(df['TX_Time'], errors='coerce')
    df['TX_Time'] = df['TX_Time'].fillna(pd.Timestamp.now())

    behavioral_cols = [
        'client_AVG_TX_Amount', 'client_AVG_TX_PerDay', 'client_amount_deviation',
        'client_abs_amount_deviation', 'client_amount_max_ratio', 'client_amount_z_score',
        'client_is_new_max', 'client_is_new_min', 'client_total_transactions',
        'client_time_since_last_tx', 'client_hours_since_last_tx', 'client_gap_z_score',
        'client_total_days_since_first',
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

    df = _normalize_categorical_values(df)

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

    df = df[expected_input_cols].copy()
    df = _normalize_categorical_values(df)
    return df


class HybridEngine:
    _explainers_cache: Dict[str, FraudExplainabilityEngine] = {}

    @staticmethod
    def predict(
        artifact_bundle: Dict[str, Any],
        features_raw: pd.DataFrame,
        include_explanation: bool = False,
        existing_behavior: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        try:
            model = artifact_bundle["model"]
            preprocessor = artifact_bundle["preprocessor"]

            features_raw = _apply_pre_pipeline_transforms(features_raw, preprocessor)
            features_raw = _normalize_categories_and_behavioral(features_raw, preprocessor)
            features_raw = _align_features(features_raw, preprocessor)

            X_trans = preprocessor.transform(features_raw)
            if hasattr(X_trans, 'toarray'):
                X_trans = X_trans.toarray()
            X_trans = np.asarray(X_trans, dtype=np.float32)

            if isinstance(model, HybridEnsembleBundle):
                result = HybridEngine._predict_hybrid(model, X_trans)
            elif hasattr(model, "predict_proba"):
                result = HybridEngine._predict_standalone_anomaly(
                    model,
                    X_trans,
                    threshold=artifact_bundle.get("threshold"),
                )
            else:
                raise ValueError(f"Type de modèle non supporté : {type(model)}")

            transaction_dict = features_raw.iloc[0].to_dict()
            if existing_behavior is not None:
                behavior_dict = existing_behavior
            else:
                behavior_dict = {k: v for k, v in transaction_dict.items() if k.startswith('client_')}

            effective_threshold = artifact_bundle.get('threshold')
            if effective_threshold is None:
                effective_threshold = getattr(model, 'threshold_', 0.5)

            result['fraud_reason'] = HybridEngine._build_rich_fraud_reason(
                artifact_bundle=artifact_bundle,
                features_raw=features_raw,
                X_trans=X_trans,
                fraud_score=result['fraud_score'],
                transaction_data=transaction_dict,
                behavior_data=behavior_dict,
                threshold=float(effective_threshold),
                row_id=0,
                include_shap=include_explanation,
                default_alert_type=result.get('alert_type', 'HYBRID_ENSEMBLE')
            )
            if include_explanation and 'alert_type' in result['fraud_reason']:
                result['alert_type'] = result['fraud_reason']['alert_type']
            return result

        except Exception as e:
            logger.error(f"Erreur d'inférence : {e}")
            raise

    @staticmethod
    def predict_batch(
        artifact_bundle: Dict[str, Any],
        features_raw: pd.DataFrame,
        include_explanation: bool = False,
        existing_behaviors: Optional[List[Optional[Dict[str, Any]]]] = None
    ) -> List[Dict[str, Any]]:
        try:
            model = artifact_bundle["model"]
            preprocessor = artifact_bundle["preprocessor"]

            features_raw = _apply_pre_pipeline_transforms(features_raw, preprocessor)
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
                
                if hasattr(model, 'copod_normalizer') and model.copod_normalizer is not None:
                    copod_scores = model.copod_normalizer.transform(copod_raw)
                elif model.copod_min is not None and model.copod_max is not None:
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
                        include_shap=include_explanation and i < 5,
                        default_alert_type='HYBRID_ENSEMBLE'
                    )

                    batch_results.append({
                        "fraud_score": score,
                        "risk_level": HybridEngine._compute_risk_level(score, threshold),
                        "alert_type": fraud_reason.get('alert_type', 'HYBRID_ENSEMBLE'),
                        "supervised_score": float((rf_scores[i] + xgb_scores[i]) / 2),
                        "anomaly_score": float(copod_scores[i]),
                        "behavioral_signals": {
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
                        },
                        "fraud_reason": fraud_reason
                    })
                return batch_results

            elif hasattr(model, "predict_proba"):
                scores = model.predict_proba(X_trans)
                if getattr(scores, "ndim", 1) == 2:
                    scores = scores[:, 1]

                threshold = artifact_bundle.get('threshold')
                if threshold is None:
                    threshold = getattr(model, 'threshold_', None)
                if threshold is None:
                    threshold = 0.5
                    logger.warning("No threshold available for standalone anomaly batch inference; fallback to 0.5")

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
                        threshold=float(threshold),
                        row_id=0,
                        include_shap=include_explanation and i < 5,
                        default_alert_type=getattr(model, 'name', 'ANOMALY').upper()
                    )

                    all_results.append({
                        "fraud_score": float(score),
                        "risk_level": HybridEngine._compute_risk_level(float(score), float(threshold)),
                        "alert_type": fraud_reason.get('alert_type', getattr(model, 'name', 'ANOMALY').upper()),
                        "supervised_score": 0.0,
                        "anomaly_score": float(score),
                        "fraud_reason": fraud_reason,
                        "behavioral_signals": {
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
                    })
                return all_results

            else:
                raise ValueError(f"Type de modèle non supporté : {type(model)}")

        except Exception as e:
            logger.error(f"Erreur d'inférence batch : {e}")
            raise

    # ═══════════════════════════════════════════════════════════════
    # EXPLAINABILITY (GUARANTEED NON-EMPTY CONTRIBUTIONS)
    # ═══════════════════════════════════════════════════════════════

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
        include_shap: bool = False,
        default_alert_type: str = 'HYBRID_ENSEMBLE'
    ) -> Dict[str, Any]:
        """
        Génère une explication enrichie en utilisant le moteur d'explicabilité avancé.
        GARANTIE : les contributions ne seront JAMAIS vides.
        """
        model_uuid = artifact_bundle.get('model_uuid', getattr(artifact_bundle.get('model'), 'model_uuid', 'default'))

        # Récupérer ou créer l'explainer
        if model_uuid not in HybridEngine._explainers_cache:
            bundle_model = artifact_bundle.get("model")
            if isinstance(bundle_model, HybridEnsembleBundle):
                xgb_model = bundle_model.get_xgb_classifier()
            else:
                xgb_model = bundle_model

            preprocessor = artifact_bundle.get("preprocessor")

            feature_names = None
            if hasattr(preprocessor, 'get_feature_names_out'):
                try:
                    feature_names = preprocessor.get_feature_names_out()
                except Exception:
                    pass
            if feature_names is None:
                feature_names = [f"feature_{i}" for i in range(X_trans.shape[1])]

            background_data = artifact_bundle.get('background_data')
            if background_data is not None and not isinstance(background_data, np.ndarray):
                background_data = np.array(background_data)

            dl_model_path = artifact_bundle.get('dl_model_path')
            dl_input_dim = artifact_bundle.get('dl_input_dim')

            try:
                explainer = FraudExplainabilityEngine(
                    model=xgb_model,
                    feature_names=feature_names,
                    background_data=background_data,
                    threshold=threshold,
                    config_path=artifact_bundle.get('explain_config_path'),
                    dl_model_path=dl_model_path,
                    dl_input_dim=dl_input_dim,
                    dl_method=artifact_bundle.get('dl_method', 'integrated_gradients')
                )
                HybridEngine._explainers_cache[model_uuid] = explainer
                logger.info("Explainability engine initialized for %s", model_uuid)
            except Exception as e:
                logger.error("Failed to init explainer: %s", e)
                return HybridEngine._build_minimal_fraud_reason(
                    fraud_score=fraud_score,
                    threshold=threshold,
                    transaction_data=transaction_data,
                    behavior_data=behavior_data,
                    default_alert_type=default_alert_type
                )

        explainer = HybridEngine._explainers_cache[model_uuid]

        features_vector = X_trans[row_id] if X_trans.ndim > 1 else X_trans
        if isinstance(features_vector, np.ndarray) and features_vector.ndim > 1:
            features_vector = features_vector.flatten()

        try:
            explanation = explainer.explain_fraud(
                features=features_vector,
                fraud_score=fraud_score,
                transaction_data=transaction_data,
                behavior_data=behavior_data
            )
        except Exception as e:
            logger.error("Explainability call failed: %s", e)
            return HybridEngine._build_minimal_fraud_reason(
                fraud_score=fraud_score,
                threshold=threshold,
                transaction_data=transaction_data,
                behavior_data=behavior_data,
                default_alert_type=default_alert_type
            )

        final_reason = explanation.get("fraud_reason_json", {})
        final_reason["alert_type"] = explanation.get("alert_type", default_alert_type)

        actual_risk_level = HybridEngine._compute_risk_level(fraud_score, threshold)
        final_reason["risk_level"] = actual_risk_level
        final_reason["fraud_score"] = round(float(fraud_score), 4)

        old_summary = final_reason.get("summary", "")
        if ":" in old_summary:
            factor_part = old_summary.split(":", 1)[1].strip()
            final_reason["summary"] = f"{actual_risk_level}: {factor_part}"
        else:
            factor_display = final_reason.get("primary_factor_display", "Anomalie détectée")
            final_reason["summary"] = f"{actual_risk_level}: {factor_display}"

        return final_reason

    @staticmethod
    def _build_minimal_fraud_reason(
        fraud_score: float,
        threshold: float,
        transaction_data: Dict[str, Any],
        behavior_data: Dict[str, Any],
        default_alert_type: str
    ) -> Dict[str, Any]:
        """
        Fallback minimal avec GARANTIE de contributions non-vides.
        Appelé uniquement si le moteur d'explicabilité échoue complètement.
        """
        risk_level = HybridEngine._compute_risk_level(fraud_score, threshold)
        total_tx = behavior_data.get('client_total_transactions', 0) if behavior_data else 0
        amount = transaction_data.get('TX_Amount', transaction_data.get('amount', 0))
        avg_amount = behavior_data.get('client_AVG_TX_Amount', 0) if behavior_data else 0

        if risk_level == "CRITICAL":
            desc = f"Transaction hautement suspecte (score {fraud_score:.1%})"
            detail = (f"Transaction de {amount} avec un score critique de {fraud_score:.1%}. "
                     f"Vérification manuelle immédiate requise.")
            severity = "high"
            points = int(fraud_score * 90)
        elif risk_level == "HIGH":
            desc = f"Anomalie comportementale détectée (score {fraud_score:.1%})"
            if total_tx == 0:
                detail = (f"Première transaction de {amount}. "
                         f"Score élevé de {fraud_score:.1%} dû à l'absence d'historique client.")
            elif total_tx <= 5:
                detail = (f"Transaction de {amount} avec un historique limité ({total_tx} transactions, "
                         f"moyenne: {avg_amount}). Score de {fraud_score:.1%}.")
            else:
                detail = (f"Transaction de {amount} (client établi: {total_tx} transactions, "
                         f"moyenne: {avg_amount}). Score de {fraud_score:.1%}.")
            severity = "high"
            points = int(fraud_score * 75)
        elif risk_level == "MEDIUM":
            desc = f"Comportement légèrement atypique (score {fraud_score:.1%})"
            if total_tx <= 5:
                detail = (f"Transaction de {amount} avec un historique client limité "
                         f"({total_tx} transactions). Score modéré de {fraud_score:.1%}.")
            else:
                detail = (f"Transaction de {amount} (client établi: {total_tx} transactions). "
                         f"Légers écarts détectés (score {fraud_score:.1%}).")
            severity = "medium"
            points = int(fraud_score * 55)
        else:
            desc = f"Transaction dans la norme (score {fraud_score:.1%})"
            if total_tx == 0:
                detail = (f"Première transaction de {amount}. "
                         f"Score faible ({fraud_score:.1%}) – conforme aux attentes.")
            else:
                detail = (f"Transaction de {amount} conforme au profil habituel "
                         f"({total_tx} transactions, moyenne: {avg_amount}). Score: {fraud_score:.1%}.")
            severity = "low"
            points = int(fraud_score * 35)

        return {
            "risk_level": risk_level,
            "fraud_score": round(float(fraud_score), 4),
            "primary_factor": "BEHAVIORAL",
            "primary_factor_display": "Analyse comportementale",
            "summary": f"{risk_level}: {desc}",
            "narrative": detail,
            "client_context": {
                "total_transactions": total_tx,
                "has_history": total_tx > 0,
                "avg_amount": round(float(avg_amount) if avg_amount else 0, 2),
                "risk_score": round(float(behavior_data.get('client_risk_score', 0)) if behavior_data else 0, 1),
                "is_established": total_tx > 5
            },
            "contributions": [{
                "factor": "BEHAVIORAL",
                "factor_display": "Analyse comportementale",
                "description": desc,
                "detail": detail,
                "impact_points": points,
                "type": "fallback",
                "severity": severity
            }],
            "total_risk_points": points,
            "analysis_source": "minimal_fallback",
            "alert_type": default_alert_type if total_tx > 0 else "NO_HISTORY",
            "explainers_used": {
                "shap": None,
                "deep_learning": False,
                "statistical": False,
                "score_based": True
            }
        }

    @staticmethod
    def _compute_risk_level(score: float, threshold: float) -> str:
        """Calcule le niveau de risque selon le seuil du modèle."""
        if score >= threshold + (1.0 - threshold) * 0.6:
            return "CRITICAL"
        if score >= threshold:
            return "HIGH"
        if score >= threshold * 0.6:
            return "MEDIUM"
        if score >= threshold * 0.3:
            return "LOW"
        return "VERY_LOW"

    @staticmethod
    def _predict_hybrid(bundle: HybridEnsembleBundle, X_trans: np.ndarray) -> Dict[str, Any]:
        rf_score = bundle.rf_model.predict_proba(X_trans)[:, 1]
        xgb_score = bundle.xgb_model.predict_proba(X_trans)[:, 1]
        copod_raw = bundle.copod_model.decision_function(X_trans)
        
        if hasattr(bundle, 'copod_normalizer') and bundle.copod_normalizer is not None:
            copod_score = bundle.copod_normalizer.transform(copod_raw)
        elif bundle.copod_min is not None and bundle.copod_max is not None:
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
    def _predict_standalone_anomaly(
        model: Any,
        X_trans: np.ndarray,
        threshold: Optional[float] = None
    ) -> Dict[str, Any]:
        scores = model.predict_proba(X_trans)
        if getattr(scores, "ndim", 1) == 2:
            scores = scores[:, 1]

        score = float(scores[0]) if len(scores) > 0 else 0.0

        effective_threshold = threshold
        if effective_threshold is None:
            effective_threshold = getattr(model, 'threshold_', None)
        if effective_threshold is None:
            effective_threshold = 0.5
            logger.warning("No probability threshold provided for standalone anomaly model; fallback to 0.5")

        return {
            "fraud_score": score,
            "risk_level": HybridEngine._compute_risk_level(score, float(effective_threshold)),
            "alert_type": getattr(model, 'name', 'ANOMALY').upper(),
            "supervised_score": 0.0,
            "anomaly_score": score,
            "behavioral_signals": {"deviation_rank": score}
        }