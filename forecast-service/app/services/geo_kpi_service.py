from app.schemas.responses import DetailsKPIs, OverviewZoneResponse
from app.services.behavior_kpi_service import compute_behavior_kpis
from app.services.business_kpi_service import compute_business_kpis
from app.services.financial_kpi_service import compute_financial_kpis
from app.services.hotspot_service import compute_risk_geo_kpis


def _resolve_risk_level(hotspot_score: float) -> str:
    """
    Convertit le score de hotspot en niveau de risque métier.
    """
    if hotspot_score < 0.30:
        return "LOW"
    if hotspot_score < 0.60:
        return "MEDIUM"
    if hotspot_score < 0.80:
        return "HIGH"
    return "CRITICAL"


def compute_overview_zone(
    postal_code: str,
    region: str,
    period: str = "next_7_days",
    horizon_days: int = 7,
) -> OverviewZoneResponse:
    """
    Orchestre le calcul des KPI prédictifs pour une zone géographique.

    Ce service agrège :
    - les KPI financiers ;
    - les KPI de risque géographique ;
    - les KPI comportementaux ;
    - les KPI business liés à l'impact CA.
    """
    horizon_days = max(1, horizon_days)

    financial_kpis = compute_financial_kpis(
        postal_code=postal_code,
        horizon_days=horizon_days,
    )

    geo_kpis = compute_risk_geo_kpis(
        postal_code=postal_code,
        horizon_days=horizon_days,
    )

    behavior_kpis = compute_behavior_kpis(
        postal_code=postal_code,
        horizon_days=horizon_days,
    )

    business_kpis = compute_business_kpis(
        postal_code=postal_code,
        horizon_days=horizon_days,
    )

    hotspot_score = geo_kpis["hotspot_score"]
    risk_level = _resolve_risk_level(hotspot_score)

    return OverviewZoneResponse(
        zoneLabel=f"{region} - {postal_code}",
        riskLevel=risk_level,
        hotspotScore=hotspot_score,
        fraudLossForecast=financial_kpis["fraud_loss_forecast"],
        revenueAtRisk=business_kpis["revenue_at_risk"],
        expectedLossPerCustomer=financial_kpis["expected_loss_per_customer"],
        highRiskCustomerRatio=behavior_kpis["high_risk_customer_ratio"],
        netSavings=financial_kpis["net_savings"],
        details=DetailsKPIs(
            costOfFalsePositives=financial_kpis["cost_of_false_positives"],
            crossBorderAnomalyRate=geo_kpis["cross_border_anomaly_rate"],
            behaviorDriftScore=behavior_kpis["behavior_drift_score"],
            predictedCaImpact=business_kpis["predicted_ca_impact"],
            transactionApprovalRate=business_kpis["approval_rate"],
            customerFrictionScore=business_kpis["friction_score"],
        ),
    )


def compute_all_kpis_for_region(
    postal_code: str,
    horizon_days: int,
) -> OverviewZoneResponse:
    """
    Fonction utilisée par les endpoints techniques /kpis et /refresh.

    Elle permet de recalculer les KPI d'une zone sans dépendre d'une région
    fournie par la carte.
    """
    horizon_days = max(1, horizon_days)

    return compute_overview_zone(
        postal_code=postal_code,
        region="Zone",
        period=f"next_{horizon_days}_days",
        horizon_days=horizon_days,
    )