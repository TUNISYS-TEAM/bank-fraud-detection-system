from app.predictors.hotspot_predictor import HotspotPredictor

hotspot_predictor = HotspotPredictor()

def compute_risk_geo_kpis(postal_code: str, horizon_days: int) -> dict:
    preds = hotspot_predictor.predict(postal_code, horizon_days)
    return {
        "cross_border_anomaly_rate": preds["cross_border_anomaly_rate"],
        "hotspot_score": preds["hotspot_score"]
    }