from schemas.responses import OverviewZoneResponse, DetailsKPIs
from services.financial_kpi_service import compute_financial_kpis
from services.hotspot_service import compute_risk_geo_kpis
from services.behavior_kpi_service import compute_behavior_kpis
from services.business_kpi_service import compute_business_kpis
from utils.date_utils import get_future_window

def compute_overview_zone(postal_code: str, region: str, period: str = "next_7_days", horizon_days: int = 7) -> OverviewZoneResponse:
    # Extraction des KPIs par service
    fin = compute_financial_kpis(postal_code, horizon_days)
    geo = compute_risk_geo_kpis(postal_code, horizon_days)
    beh = compute_behavior_kpis(postal_code, horizon_days)
    biz = compute_business_kpis(postal_code, horizon_days)

    # Détermination du niveau de risque
    score = geo["hotspot_score"]
    if score < 0.3:
        risk = "LOW"
    elif score < 0.6:
        risk = "MEDIUM"
    elif score < 0.8:
        risk = "HIGH"
    else:
        risk = "CRITICAL"

    return OverviewZoneResponse(
        zoneLabel=f"{region} - {postal_code}",
        riskLevel=risk,
        hotspotScore=geo["hotspot_score"],
        fraudLossForecast=fin["fraud_loss_forecast"],
        revenueAtRisk=biz["revenue_at_risk"],
        expectedLossPerCustomer=fin["expected_loss_per_customer"],
        highRiskCustomerRatio=beh["high_risk_customer_ratio"],
        netSavings=fin["net_savings"],
        details=DetailsKPIs(
            costOfFalsePositives=fin["cost_of_false_positives"],
            crossBorderAnomalyRate=geo["cross_border_anomaly_rate"],
            behaviorDriftScore=beh["behavior_drift_score"],
            predictedCaImpact=biz["predicted_ca_impact"],
            transactionApprovalRate=biz["approval_rate"],
            customerFrictionScore=biz["friction_score"]
        )
    )