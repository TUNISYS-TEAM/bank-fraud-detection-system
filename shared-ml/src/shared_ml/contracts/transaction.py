from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field, ConfigDict


class TransactionInput(BaseModel):
    """
    Canonical input contract for one transaction sent to the inference layer.

    Internal field names stay in snake_case.
    Aliases allow compatibility with dataset/backend naming conventions.
    """
    model_config = ConfigDict(
        populate_by_name=True,
        extra="forbid"
    )

    transaction_id: str | None = None
    account_id: str = Field(..., alias="Account_Id")
    tx_time: datetime = Field(..., alias="TX_Time")
    tx_amount: float = Field(..., gt=0, alias="TX_Amount")
    customer_postal_code: str | None = Field(None, alias="Customer_Postal_Code")
    channel: str = Field(..., alias="Channel")
    category: str

    # Optional precomputed features from backend or feature service
    client_avg_tx_per_day: float | None = None
    client_avg_tx_amount: float | None = None
    client_hours_since_last_tx: float | None = None
    client_usual_region: str | None = None
    client_risk_score: float | None = Field(None, ge=0, le=1)


class PredictionOutput(BaseModel):
    """
    Standard prediction response for one transaction.
    """
    model_config = ConfigDict(
        extra="forbid"
    )

    transaction_id: str | None = None
    account_id: str
    fraud_probability: float = Field(..., ge=0, le=1)
    fraud_score: float | None = None
    threshold: float = Field(..., ge=0, le=1)
    is_fraud: bool
    model_name: str
    model_version: str = "1.0.0"
    risk_score: float | None = Field(None, ge=0, le=1)
    explanation: dict[str, Any] = Field(default_factory=dict)
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class BatchPredictionRequest(BaseModel):
    """
    Batch inference request.
    """
    model_config = ConfigDict(
        extra="forbid"
    )

    transactions: list[TransactionInput]
    model_name: str = "ensemble"


class BatchPredictionResponse(BaseModel):
    """
    Batch inference response.
    """
    model_config = ConfigDict(
        extra="forbid"
    )

    predictions: list[PredictionOutput]
    model_name: str
    total_transactions: int = Field(..., ge=0)
    total_flagged: int = Field(..., ge=0)
    processing_time_ms: float = Field(default=0.0, ge=0)