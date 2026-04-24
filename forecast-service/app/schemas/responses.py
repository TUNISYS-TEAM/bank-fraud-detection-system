from pydantic import BaseModel
from typing import Optional

class DetailsKPIs(BaseModel):
    costOfFalsePositives: float
    crossBorderAnomalyRate: float
    behaviorDriftScore: float
    predictedCaImpact: float
    transactionApprovalRate: float
    customerFrictionScore: float

class OverviewZoneResponse(BaseModel):
    zoneLabel: str
    riskLevel: str                # "LOW", "MEDIUM", "HIGH", "CRITICAL"
    hotspotScore: float
    fraudLossForecast: float
    revenueAtRisk: float
    expectedLossPerCustomer: float
    highRiskCustomerRatio: float
    netSavings: float
    details: DetailsKPIs