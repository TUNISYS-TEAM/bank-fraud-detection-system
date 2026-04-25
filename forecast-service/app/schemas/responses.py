from pydantic import BaseModel
from typing import Optional

class DetailsKPIs(BaseModel):
    cost_of_false_positives: Optional[float] = None
    cross_border_anomaly_rate: Optional[float] = None
    behavior_drift_score: Optional[float] = None
    predicted_ca_impact: Optional[float] = None
    transaction_approval_rate: Optional[float] = None
    customer_friction_score: Optional[float] = None

class OverviewZoneResponse(BaseModel):
    postal_code: Optional[str] = None
    zone_label: str
    risk_level: Optional[str] = None
    hotspot_score: Optional[float] = None
    total_anomalies: Optional[int] = None         
    fraud_loss_forecast: Optional[float] = None
    revenue_at_risk: Optional[float] = None
    expected_loss_per_customer: Optional[float] = None
    high_risk_customer_ratio: Optional[float] = None
    net_savings: Optional[float] = None
    details: DetailsKPIs