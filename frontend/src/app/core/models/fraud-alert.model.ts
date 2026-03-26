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