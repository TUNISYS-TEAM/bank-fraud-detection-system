"""
Stockage et remplacement atomique des modèles.
"""

import os
import joblib
import logging
from datetime import datetime

logger = logging.getLogger(__name__)

MODEL_DIR = "/app/models"


def _get_model_path(model_name: str) -> str:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"{model_name}_{timestamp}.pkl"
    return os.path.join(MODEL_DIR, filename)


def save_model(model, model_name: str) -> str:
    path = _get_model_path(model_name)
    os.makedirs(MODEL_DIR, exist_ok=True)
    joblib.dump(model, path)
    logger.info("Model saved: %s", path)
    return path


def load_current_model(model_name: str):
    pointer_file = os.path.join(MODEL_DIR, f"current_{model_name}.txt")
    if not os.path.exists(pointer_file):
        logger.warning("No current model pointer for %s", model_name)
        return None
    with open(pointer_file, "r") as f:
        model_path = f.read().strip()
    if not os.path.exists(model_path):
        logger.warning("Model file missing: %s", model_path)
        return None
    return joblib.load(model_path)


def promote_model(new_model_path: str, model_name: str) -> None:
    pointer_file = os.path.join(MODEL_DIR, f"current_{model_name}.txt")
    with open(pointer_file, "w") as f:
        f.write(new_model_path)
    static_path = os.path.join(MODEL_DIR, f"{model_name}.pkl")
    joblib.dump(joblib.load(new_model_path), static_path)
    logger.info("Promoted model %s -> %s", model_name, new_model_path)


def _reload_services():
    try:
        from app.services.financial_kpi_service import loss_forecaster
        from app.services.hotspot_service import hotspot_predictor
        from app.services.business_kpi_service import revenue_predictor
        loss_forecaster.reload()
        hotspot_predictor.reload()
        revenue_predictor.reload()
        logger.info("All predictors reloaded successfully")
    except Exception as e:
        logger.warning("Could not reload predictors: %s", e)


def retrain_and_replace(transactions: list) -> dict:
    from app.training.features import (
        build_features_for_loss, build_target_loss,
        build_features_for_hotspot, build_target_hotspot,
        build_features_for_revenue, build_target_revenue,
    )
    from app.training.trainer import train_loss_forecaster, train_hotspot_predictor, train_revenue_predictor
    from app.training.evaluator import evaluate_loss_model, evaluate_hotspot_model, evaluate_revenue_model

    results = {}

    # --- Loss Forecaster ---
    X_loss = build_features_for_loss(transactions)
    y_loss = build_target_loss(transactions)
    split_idx = int(len(X_loss) * 0.8)
    X_train, X_test = X_loss[:split_idx], X_loss[split_idx:]
    y_train, y_test = y_loss[:split_idx], y_loss[split_idx:]
    new_loss = train_loss_forecaster(X_train, y_train)
    curr_loss = load_current_model("loss_forecaster")
    if curr_loss is None or evaluate_loss_model(new_loss, curr_loss, X_test, y_test):
        path = save_model(new_loss, "loss_forecaster")
        promote_model(path, "loss_forecaster")
        results["loss_forecaster"] = "replaced"
    else:
        results["loss_forecaster"] = "kept"

    # --- Hotspot Predictor ---
    X_hot = build_features_for_hotspot(transactions)
    y_hot = build_target_hotspot(transactions)
    split_idx = int(len(X_hot) * 0.8)
    X_train, X_test = X_hot[:split_idx], X_hot[split_idx:]
    y_train, y_test = y_hot[:split_idx], y_hot[split_idx:]
    new_hot = train_hotspot_predictor(X_train, y_train)
    curr_hot = load_current_model("hotspot_predictor")
    if curr_hot is None or evaluate_hotspot_model(new_hot, curr_hot, X_test, y_test):
        path = save_model(new_hot, "hotspot_predictor")
        promote_model(path, "hotspot_predictor")
        results["hotspot_predictor"] = "replaced"
    else:
        results["hotspot_predictor"] = "kept"

    # --- Revenue Impact Predictor ---
    X_rev = build_features_for_revenue(transactions)
    y_rev = build_target_revenue(transactions)
    split_idx = int(len(X_rev) * 0.8)
    X_train, X_test = X_rev[:split_idx], X_rev[split_idx:]
    y_train, y_test = y_rev[:split_idx], y_rev[split_idx:]
    new_rev = train_revenue_predictor(X_train, y_train)
    curr_rev = load_current_model("revenue_impact_predictor")
    if curr_rev is None or evaluate_revenue_model(new_rev, curr_rev, X_test, y_test):
        path = save_model(new_rev, "revenue_impact_predictor")
        promote_model(path, "revenue_impact_predictor")
        results["revenue_impact_predictor"] = "replaced"
    else:
        results["revenue_impact_predictor"] = "kept"

    _reload_services()
    return results