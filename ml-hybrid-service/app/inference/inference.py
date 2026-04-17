from fastapi import APIRouter, HTTPException
import pandas as pd
import logging
from typing import Dict, Any

from app.models.model_registry import registry
from app.inference.hybrid_engine import HybridEngine
from app.inference.preprocessing import FeatureEngineer # Nouvel Import
from shared_ml.contracts.prediction import (
    PredictRequest, BatchPredictRequest, PredictResponse, BatchPredictResponse
)

router = APIRouter()
logger = logging.getLogger(__name__)


@router.post("/predict", response_model=PredictResponse)
async def predict(request: PredictRequest):
    """Inférence pour une seule transaction avec validation Pydantic."""
    try:
        model_uuid = request.model_uuid
        metadata = {
            "model_path": request.storage_path,
            "pipeline_path": request.pipeline_path
        }
        
        artifact_bundle = registry.get_model(model_uuid, metadata)

        # Utilisation de FeatureEngineer pour la transformation
        features_df = FeatureEngineer.build_features(
            request.transaction, request.existing_behavior
        )
        
        result = HybridEngine.predict(artifact_bundle, features_df)
        return result

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Prediction error")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/predict-batch", response_model=BatchPredictResponse)
async def predict_batch(request: BatchPredictRequest):
    """Inférence par lot avec validation Pydantic."""
    try:
        model_uuid = request.model_uuid
        metadata = {
            "model_path": request.storage_path,
            "pipeline_path": request.pipeline_path
        }

        artifact_bundle = registry.get_model(model_uuid, metadata)

        all_results = []
        for item in request.batch:
            # Transformation unitaire du batch
            features_df = FeatureEngineer.build_features(
                item.transaction, item.existing_behavior
            )
            
            prediction = HybridEngine.predict(artifact_bundle, features_df)
            all_results.append(prediction)

        return BatchPredictResponse(results=all_results)

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Batch prediction error")
        raise HTTPException(status_code=500, detail=str(e))