"""
Évaluation comparée d'un nouveau modèle avec le modèle courant.
Métriques : MAE, RMSE, R².
"""

import numpy as np
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
import logging

logger = logging.getLogger(__name__)


def _evaluate(new_model, current_model, X_val: np.ndarray, y_val: np.ndarray, model_name: str) -> bool:
    """Retourne True si new_model est meilleur (MAE inférieure de plus de 2%)."""
    new_preds = new_model.predict(X_val)
    curr_preds = current_model.predict(X_val)

    if y_val.ndim > 1:
        # Pour multi-output, on aplatit
        new_preds = new_preds.ravel()
        curr_preds = curr_preds.ravel()
        y_val = y_val.ravel()

    new_mae = mean_absolute_error(y_val, new_preds)
    curr_mae = mean_absolute_error(y_val, curr_preds)
    new_rmse = np.sqrt(mean_squared_error(y_val, new_preds))
    curr_rmse = np.sqrt(mean_squared_error(y_val, curr_preds))
    new_r2 = r2_score(y_val, new_preds)
    curr_r2 = r2_score(y_val, curr_preds)

    improvement = (curr_mae - new_mae) / curr_mae * 100 if curr_mae > 0 else 0

    logger.info(
        "%s evaluation:\n"
        "  Current: MAE=%.4f, RMSE=%.4f, R²=%.4f\n"
        "  New:     MAE=%.4f, RMSE=%.4f, R²=%.4f\n"
        "  Improvement: %.1f%%",
        model_name, curr_mae, curr_rmse, curr_r2, new_mae, new_rmse, new_r2, improvement
    )

    # On remplace seulement si amélioration > 2% (évite le remplacement pour du bruit)
    return improvement > 2.0


def evaluate_loss_model(new_model, current_model, X_val: np.ndarray, y_val: np.ndarray) -> bool:
    return _evaluate(new_model, current_model, X_val, y_val, "LossModel")


def evaluate_hotspot_model(new_model, current_model, X_val: np.ndarray, y_val: np.ndarray) -> bool:
    return _evaluate(new_model, current_model, X_val, y_val, "HotspotModel")


def evaluate_revenue_model(new_model, current_model, X_val: np.ndarray, y_val: np.ndarray) -> bool:
    return _evaluate(new_model, current_model, X_val, y_val, "RevenueModel")