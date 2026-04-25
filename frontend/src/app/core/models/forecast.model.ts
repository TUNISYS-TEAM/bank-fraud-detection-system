export interface DetailsKPIs {
  cost_of_false_positives: number | null;
  cross_border_anomaly_rate: number | null;
  behavior_drift_score: number | null;
  predicted_ca_impact: number | null;
  transaction_approval_rate: number | null;
  customer_friction_score: number | null;
}

export interface ForecastKpis {
  postal_code: string | null;
  zone_label: string;
  risk_level: string | null;
  hotspot_score: number | null;
  total_anomalies: number | null;    
  fraud_loss_forecast: number | null;
  revenue_at_risk: number | null;
  expected_loss_per_customer: number | null;
  high_risk_customer_ratio: number | null;
  net_savings: number | null;
  details: DetailsKPIs;
}