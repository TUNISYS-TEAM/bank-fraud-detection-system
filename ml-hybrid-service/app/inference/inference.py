from fastapi import APIRouter, HTTPException, Query
import pandas as pd
import logging
from typing import Dict, Any, Optional

from app.models.model_registry import registry
from app.inference.hybrid_engine import HybridEngine
from shared_ml.contracts.prediction import (
    PredictRequest, BatchPredictRequest, PredictResponse, BatchPredictResponse
)

router = APIRouter()
logger = logging.getLogger(__name__)


@router.post("/predict", response_model=PredictResponse)
async def predict(
    request: PredictRequest,
    include_explanation: bool = Query(False, description="Include SHAP-based explanation")
):
    try:
        model_uuid = request.model_uuid
        metadata = {
            "model_path": request.storage_path,
            "pipeline_path": request.pipeline_path,
            "model_uuid": model_uuid
        }
        artifact_bundle = registry.get_model(model_uuid, metadata)

        transaction_dict = request.transaction.dict() if hasattr(request.transaction, 'dict') else request.transaction
        features_df = pd.DataFrame([transaction_dict])

        existing_behavior = None
        if request.existing_behavior:
            existing_behavior = request.existing_behavior.dict() if hasattr(request.existing_behavior, 'dict') else request.existing_behavior
            # Optionnel : ajouter au DataFrame pour la compatibilité
            for key, value in existing_behavior.items():
                if key in features_df.columns:
                    features_df.at[0, key] = value
                else:
                    features_df[key] = value

        result = HybridEngine.predict(
            artifact_bundle,
            features_df,
            include_explanation=include_explanation,
            existing_behavior=existing_behavior   # ← CRUCIAL
        )
        return result

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Prediction error")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/predict-batch", response_model=BatchPredictResponse)
async def predict_batch(
    request: BatchPredictRequest,
    include_explanation: bool = Query(False, description="Include SHAP explanations (first 5 items)")
):
    try:
        model_uuid = request.model_uuid
        metadata = {
            "model_path": request.storage_path,
            "pipeline_path": request.pipeline_path,
            "model_uuid": model_uuid
        }
        artifact_bundle = registry.get_model(model_uuid, metadata)

        transactions = [item.transaction.dict() if hasattr(item.transaction, 'dict') else item.transaction
                        for item in request.batch]
        features_df = pd.DataFrame(transactions)

        behaviors = []
        for i, item in enumerate(request.batch):
            behavior = None
            if item.existing_behavior:
                behavior = item.existing_behavior.dict() if hasattr(item.existing_behavior, 'dict') else item.existing_behavior
                for key, value in behavior.items():
                    if key in features_df.columns:
                        features_df.at[i, key] = value
                    else:
                        features_df[key] = value
            behaviors.append(behavior)

        results = HybridEngine.predict_batch(
            artifact_bundle,
            features_df,
            include_explanation=include_explanation,
            existing_behaviors=behaviors   # ← CRUCIAL
        )
        return BatchPredictResponse(results=results)

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Batch prediction error")
        raise HTTPException(status_code=500, detail=str(e))