export interface FraudAlertResponse {
  id: number;
  alertUuid: string;
  transactionId: number;
  userId: number;
  fraudScore: number;
  supervisedScore: number;
  anomalyScore: number;
  modelUsedId: number;
  riskLevel: string;
  alertType: string;
  status: string;
  reviewedBy?: number;
  reviewedByName?: string;
  reviewDate?: string;
  reviewComment?: string;
  actionTaken?: string;
  featuresSnapshot?: Record<string, any>;
  createdAt: string;
  updatedAt: string;
  transactionAmount: number;
  transactionCurrency: string;
  transactionChannel: string;
  transactionCategory: string;
  clientName?: string;
  accountNumber?: string;
  fraudReason?: any; 
  assignedManagerName?: string;
  assignedAnalystId?: number;
  assignedAnalystName?: string;
}

export interface FraudContribution {
  type: string;
  factor: string;
  description: string;
  impact_points: number;
  factor_display: string;
}

export interface StructuredFraudReason {
  summary: string;
  contributions: FraudContribution[];
  primary_factor: string;
  total_risk_points: number;
  threshold_critical: number;
  primary_factor_display: string;
}

export interface FraudLocationResponse {
  postalCode: string;
  fraudCount: number;
  lat: number;
  lon: number;
}

export interface AlertStatsResponse {
  total: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
  pending: number;
  reviewed: number;
  resolved: number;
  falsePositive: number;
}