import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { FraudAlertResponse, AlertStatsResponse } from '../models/fraud-alert.model';

@Injectable({
  providedIn: 'root'
})
export class FraudAlertService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/fraud-alerts`;

  getAlertStats(): Observable<AlertStatsResponse> {
    return this.http.get<AlertStatsResponse>(`${this.apiUrl}/stats`);
  }

  getTimeSeriesData(startDate?: string, endDate?: string): Observable<any[]> {
    let params = new HttpParams();
    if (startDate) params = params.set('startDate', startDate);
    if (endDate) params = params.set('endDate', endDate);
    return this.http.get<any[]>(`${this.apiUrl}/time-series`, { params });
  }

  getAlerts(
    riskLevel?: string,
    status?: string,
    alertType?: string,
    search?: string,
    page: number = 0,
    size: number = 20,
    sortBy: string = 'createdAt',
    sortDirection: string = 'DESC'
  ): Observable<any> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString())
      .set('sortBy', sortBy)
      .set('sortDirection', sortDirection);

    if (riskLevel && riskLevel !== 'all') params = params.set('riskLevel', riskLevel);
    if (status && status !== 'all') params = params.set('status', status);
    if (alertType) params = params.set('alertType', alertType);
    if (search) params = params.set('search', search);

    return this.http.get<any>(this.apiUrl, { params });
  }

  updateAlertStatus(alertUuid: string, request: { status: string; reviewComment: string; actionTaken: string }): Observable<FraudAlertResponse> {
    return this.http.put<FraudAlertResponse>(`${this.apiUrl}/${alertUuid}/status`, request);
  }

  getAlertByUuid(alertUuid: string): Observable<FraudAlertResponse> {
    return this.http.get<FraudAlertResponse>(`${this.apiUrl}/${alertUuid}`);
  }

  getRecentAnomalies(limit: number = 8): Observable<FraudAlertResponse[]> {
    return this.http.get<FraudAlertResponse[]>(`${this.apiUrl}/recent`, {
      params: new HttpParams().set('limit', limit.toString())
    });
  }
}