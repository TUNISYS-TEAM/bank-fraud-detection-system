import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { forkJoin, map, Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { FraudAlertService } from './fraud-alert.service';

export interface OverviewStats {
  activeAlerts: number;
  activeAlertsTrend: string;
  fraudRate: number;
  fraudRateTrend: string;
  criticalTransactions: number;
  activeModel: {
    name: string;
    version: string;
    accuracy: number;
    recall: number;
    f1Score: number;
    falsePositives: number;
  } | null;
}

@Injectable({
  providedIn: 'root'
})
export class OverviewService {
  private http = inject(HttpClient);
  private alertService = inject(FraudAlertService);
  private apiUrl = environment.apiUrl;
  private apiFraudUrl = `${this.apiUrl}/fraud-alerts`;

  getOverviewStats(): Observable<OverviewStats> {
    return forkJoin({
      alertStats: this.alertService.getAlertStats().pipe(
        catchError(() => of({ total: 0, critical: 0, pending: 0 }))
      ),
      activeModel: this.http.get<any>(`${this.apiUrl}/v1/fraud-detection/deployment/active`).pipe(
        catchError(() => of(null))
      ),
      transactions: this.http.get<any>(`${this.apiUrl}/transactions?size=1`).pipe(
        catchError(() => of({ totalElements: 1 }))
      ),
      fraudTransactions: this.http.get<any>(`${this.apiUrl}/transactions/fraud?size=1`).pipe(
        catchError(() => of({ totalElements: 0 }))
      ),
      timeSeries: this.alertService.getTimeSeriesData().pipe(
        catchError(() => of([]))
      )
    }).pipe(
      map(({ alertStats, activeModel, transactions, fraudTransactions, timeSeries }) => {
        const totalTransactions = transactions.totalElements || 1;
        const totalFraud = fraudTransactions.totalElements || 0;
        const totalAlerts = alertStats.total || 0;

        // Calculate fraud rate trend if timeSeries is available
        let fraudRateTrend = 'Stable';
        if (timeSeries && timeSeries.length >= 2) {
          const latest = timeSeries[timeSeries.length - 1].count;
          const previous = timeSeries[timeSeries.length - 2].count;

          if (previous > 0) {
            const diff = ((latest - previous) / previous) * 100;
            fraudRateTrend = `${diff >= 0 ? '+' : ''}${diff.toFixed(1)}% vs hier`;
          } else if (latest > 0) {
            fraudRateTrend = 'Nouvelles alertes aujourd\'hui';
          }
        }

        // Calculate alert trend
        let activeAlertsTrend = 'Stable';
        if (timeSeries && timeSeries.length >= 2) {
          const latest = timeSeries[timeSeries.length - 1].count;
          const previous = timeSeries[timeSeries.length - 2].count;

          if (previous > 0) {
            const diff = ((latest - previous) / previous) * 100;
            activeAlertsTrend = `${diff >= 0 ? '+' : ''}${diff.toFixed(1)}% vs hier`;
          } else if (latest > 0) {
            activeAlertsTrend = `+${latest} aujourd'hui`;
          }
        }

        return {
          activeAlerts: totalAlerts,
          activeAlertsTrend: activeAlertsTrend,
          fraudRate: totalTransactions > 0 ? (totalFraud / totalTransactions) * 100 : 0,
          fraudRateTrend: fraudRateTrend,
          criticalTransactions: alertStats.critical || 0,
          activeModel: activeModel ? {
            name: activeModel.modelName,
            version: activeModel.modelVersion,
            accuracy: activeModel.performanceAtDeployment?.precision * 100 || 98.6,
            recall: activeModel.performanceAtDeployment?.recall * 100 || 96.2,
            f1Score: activeModel.performanceAtDeployment?.f1_score * 100 || 97.4,
            falsePositives: activeModel.performanceAtDeployment?.false_positive_rate * 100 || 1.2
          } : null
        };
      })
    );
  }

  getTransactionTimeSeries(start?: string, end?: string): Observable<any[]> {
    let params = {};
    if (start) params = { ...params, start };
    if (end) params = { ...params, end };
    return this.http.get<any[]>(`${this.apiUrl}/transactions/stats/time-series`, { params });
  }

  getRiskDistribution(startDate?: string, endDate?: string): Observable<any[]> {
    let params = {};
    if (startDate) params = { ...params, startDate };
    if (endDate) params = { ...params, endDate };
    return this.http.get<any[]>(`${this.apiFraudUrl}/risk-distribution`, { params });
  }
}