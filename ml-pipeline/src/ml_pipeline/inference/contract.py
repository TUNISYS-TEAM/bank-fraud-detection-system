"""
Data contracts for inference input and output.
These models are used for validation and documentation.
"""

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field, validator


class TransactionInput(BaseModel):
    """
    Raw transaction as received from the payment system.
    All fields are required unless marked optional.
    """
    transaction_id: str
    account_id: str = Field(..., alias="Account_Id")         
    timestamp: datetime = Field(..., alias="TX_Time")
    amount: float = Field(..., gt=0, alias="TX_Amount")
    postal_code: Optional[str] = Field(None, alias="Customer_Postal_Code")
    channel: str = Field(..., alias="Channel")
    category: str

    # Pre‑computed fields (may be absent if backend does not supply them)
    client_avg_tx_per_day: Optional[float] = None
    client_avg_tx_amount: Optional[float] = None
    client_hours_since_last_tx: Optional[float] = None
    client_usual_region: Optional[str] = None
    client_risk_score: Optional[float] = Field(None, ge=0, le=1)

    class Config:
        allow_population_by_field_name = True
        extra = "forbid"          # no unexpected fields


class PredictionOutput(BaseModel):
    """
    Standardised prediction response.
    """
    transaction_id: str
    fraud_probability: float = Field(..., ge=0, le=1)
    fraud_score: float                     # raw model score before calibration
    threshold: float                        # threshold used to decide is_fraud
    is_fraud: bool
    model_name: str
    model_version: str = "1.0.0"            # could be taken from config
    timestamp: datetime = Field(default_factory=datetime.utcnow)

    class Config:
        json_encoders = {
            datetime: lambda v: v.isoformat()
        }