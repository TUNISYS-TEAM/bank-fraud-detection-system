# src/ml_pipeline/config/schemas.py
from pydantic import BaseModel, Field, validator
from typing import List, Optional, Dict, Any
from datetime import datetime


class TransactionFeatures(BaseModel):
    """Raw transaction features before transformation."""
    transaction_id: str
    client_id: str
    timestamp: datetime
    amount: float = Field(..., gt=0, description="Transaction amount in EUR")
    category: str
    device_type: Optional[str] = None
    postal_code: Optional[str] = None

    # These fields may be pre‑computed by the backend
    client_avg_tx_per_day: Optional[float] = None
    client_avg_tx_amount: Optional[float] = None
    client_hours_since_last_tx: Optional[float] = None
    client_usual_region: Optional[str] = None
    client_risk_score: Optional[float] = Field(None, ge=0, le=1)

    class Config:
        extra = "forbid"


class TransformedFeatures(BaseModel):
    """Features after all transformations (ready for model input)."""
    # Names and types correspond to what the model expects
    TX_Amount: float
    client_avg_tx_per_day: float
    client_usual_region_encoded: int
    category_encoded: int
    hour_sin: float
    hour_cos: float
    day_of_week: int
    client_risk_score: float
    client_hours_since_last_tx: float
    client_avg_tx_amount: float

    class Config:
        extra = "forbid"
        arbitrary_types_allowed = True


class PredictionOutput(BaseModel):
    transaction_id: str
    fraud_probability: float = Field(..., ge=0, le=1)
    fraud_score: float
    threshold: float
    is_fraud: bool
    model_name: str
    version: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)

    class Config:
        json_encoders = {
            datetime: lambda v: v.isoformat()
        }


class TrainingRecord(BaseModel):
    """One row of training data (features + label)."""
    transaction_id: str
    features: Dict[str, Any]
    label: int = Field(..., ge=0, le=1)
    timestamp: datetime


class FeatureImportance(BaseModel):
    feature_name: str
    importance: float


class ModelMetadata(BaseModel):
    model_name: str
    training_date: datetime
    features: List[str]
    hyperparameters: Dict[str, Any]
    performance: Dict[str, float]
    feature_importance: List[FeatureImportance]