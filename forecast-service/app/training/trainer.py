"""
Module d'entraînement des modèles XGBoost – Production Ready.
Adapte automatiquement le scale_pos_weight selon le déséquilibre des classes.
"""

import logging
import numpy as np
import xgboost as xgb
from sklearn.multioutput import MultiOutputRegressor

logger = logging.getLogger(__name__)

# Hyperparamètres de base (sans early_stopping_rounds, ajouté dynamiquement)
BASE_XGB_PARAMS = {
    'n_estimators': 300,
    'max_depth': 6,
    'learning_rate': 0.05,
    'subsample': 0.8,
    'colsample_bytree': 0.8,
    'reg_alpha': 0.1,
    'reg_lambda': 1.0,
    'random_state': 42,
    'verbosity': 0,
    'n_jobs': -1,
}


def _compute_scale_pos_weight(y: np.ndarray) -> float:
    """
    Calcule le poids des échantillons positifs (fraude) pour gérer le déséquilibre.
    Si aucune fraude ou toutes fraudes → retourne 1 (pas d'ajustement).
    """
    positives = int(np.sum(y > 0))
    negatives = len(y) - positives

    if positives == 0 or negatives == 0:
        return 1.0

    ratio = negatives / positives
    clamped = min(ratio, 50.0)
    logger.info("Déséquilibre: %d fraudes / %d légitimes → scale_pos_weight=%.1f",
                positives, negatives, clamped)
    return clamped


def _build_params(y: np.ndarray, use_early_stopping: bool = True) -> dict:
    """Construit les hyperparamètres avec scale_pos_weight adapté au déséquilibre."""
    params = BASE_XGB_PARAMS.copy()
    if y.ndim == 1:
        params['scale_pos_weight'] = _compute_scale_pos_weight(y)
    if use_early_stopping:
        params['early_stopping_rounds'] = 20
    return params


def train_loss_forecaster(X: np.ndarray, y: np.ndarray):
    """Entraîne un XGBoost Regressor pour la prévision des pertes."""
    model = xgb.XGBRegressor(**_build_params(y))
    split_idx = int(len(X) * 0.8)
    model.fit(
        X[:split_idx], y[:split_idx],
        eval_set=[(X[split_idx:], y[split_idx:])],
        verbose=False
    )
    logger.info("LossForecaster trained: %d samples", len(X))
    return model


def train_hotspot_predictor(X: np.ndarray, y: np.ndarray):
    """Entraîne un XGBoost Regressor pour le score de hotspot."""
    model = xgb.XGBRegressor(**_build_params(y))
    split_idx = int(len(X) * 0.8)
    model.fit(
        X[:split_idx], y[:split_idx],
        eval_set=[(X[split_idx:], y[split_idx:])],
        verbose=False
    )
    logger.info("HotspotPredictor trained: %d samples", len(X))
    return model


def train_revenue_predictor(X: np.ndarray, y: np.ndarray):
    """Entraîne un modèle multi-output pour l'impact CA (sans early stopping)."""
    first_col = y[:, 0] if y.ndim > 1 else y
    params = _build_params(first_col, use_early_stopping=False)
    base = xgb.XGBRegressor(**params)
    model = MultiOutputRegressor(base)
    model.fit(X, y)
    logger.info("RevenuePredictor trained: %d samples", len(X))
    return model