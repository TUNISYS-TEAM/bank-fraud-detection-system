from pydantic import BaseModel
from typing import List, Dict, Optional, Any

class PredictRequest(BaseModel):
    model_config = {'protected_namespaces': ()}
    model_uuid: str
    storage_path: Optional[str] = None
    pipeline_path: Optional[str] = None # Support pour les modèles avec pipeline séparé
    transaction: Dict[str, Any]
    has_existing_behavior: bool = False
    existing_behavior: Optional[Dict[str, Any]] = None

class BatchPredictItem(BaseModel):
    transaction: Dict[str, Any]
    has_existing_behavior: bool = False
    existing_behavior: Optional[Dict[str, Any]] = None

class BatchPredictRequest(BaseModel):
    model_config = {'protected_namespaces': ()}
    model_uuid: str
    storage_path: Optional[str] = None
    pipeline_path: Optional[str] = None # Support batch
    batch: List[BatchPredictItem]

class PredictResponse(BaseModel):
    fraud_score: float
    risk_level: str
    alert_type: str
    supervised_score: Optional[float] = None
    anomaly_score: Optional[float] = None
    fraud_reason: Dict[str, Any]
    behavioral_signals: Dict[str, Any]

class BatchPredictResponse(BaseModel):
    results: List[PredictResponse]