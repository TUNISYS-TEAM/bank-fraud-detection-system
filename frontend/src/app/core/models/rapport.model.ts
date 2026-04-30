export interface Rapport {
  id: number;
  rapportUuid: string;
  title: string;
  type: 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'ON_DEMAND';
  format: string;
  description: string;
  status: 'PENDING' | 'GENERATING' | 'COMPLETED' | 'FAILED';
  startDate: string;
  endDate: string;
  totalTransactions?: number;
  fraudDetected?: number;
  avgFraudScore?: number;
  dataSummary?: any;
  createdAt: string;
  generatedByName: string;
  generatedByEmail?: string;
  generatedById: number;
  isRead?: boolean;
  recipients?: RapportRecipient[];
}

export interface RapportRecipient {
  recipientId: number;
  recipientName: string;
  recipientRole?: string;
  isRead: boolean;
  readAt?: string;
}

export interface GenerateRapportRequest {
  title: string;
  type: string;
  description?: string;
  startDate?: string;
  endDate?: string;
  recipientIds?: number[];
}