from pydantic import BaseModel
from typing import Optional, List

class OverviewZoneRequest(BaseModel):
    postalCode: str
    region: str
    period: str              # ex: "next_7_days"
    modelId: Optional[str] = "current-production-model"

class RefreshRequest(BaseModel):
    horizon_days: Optional[int] = 7
    postalCodes: Optional[List[str]] = None