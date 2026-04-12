import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { FraudAlertResponse, AlertStatsResponse, FraudLocationResponse } from '../models/fraud-alert.model';
import { AuthService } from './auth.service';

export interface AnalystInfo {
  id: number;
  name: string;
  email: string;
  assignedCount: number;
}

@Injectable({
  providedIn: 'root'
})
export class FraudAlertService {
  private http = inject(HttpClient);
  private authService = inject(AuthService);
  private apiUrl = `${environment.apiUrl}/fraud-alerts`;

  getAlertStats(): Observable<AlertStatsResponse> {
    const teamContext = this.authService.getTeamContext();
    let params = new HttpParams();
    if (teamContext.managerId) params = params.set('managerId', teamContext.managerId.toString());

    return this.http.get<AlertStatsResponse>(`${this.apiUrl}/stats`, { params });
  }

  getTimeSeriesData(startDate?: string, endDate?: string): Observable<any[]> {
    const teamContext = this.authService.getTeamContext();
    let params = new HttpParams();
    if (startDate) params = params.set('startDate', startDate);
    if (endDate) params = params.set('endDate', endDate);
    if (teamContext.managerId) params = params.set('managerId', teamContext.managerId.toString());

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
    sortDirection: string = 'DESC',
    datasetUuid?: string
  ): Observable<any> {
    const user = this.authService.getCurrentUser();
    const isAnalyst = user?.role === 'ANALYST';
    const teamContext = this.authService.getTeamContext();

    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString())
      .set('sortBy', sortBy)
      .set('sortDirection', sortDirection);

    if (riskLevel && riskLevel !== 'all') params = params.set('riskLevel', riskLevel);
    if (status && status !== 'all') params = params.set('status', status);
    if (alertType) params = params.set('alertType', alertType);
    if (search) params = params.set('search', search);
    if (datasetUuid) params = params.set('datasetUuid', datasetUuid);
    // Only send managerId for non-analyst roles; analysts are filtered server-side by assignedAnalyst
    if (!isAnalyst && teamContext.managerId) params = params.set('managerId', teamContext.managerId.toString());

    return this.http.get<any>(this.apiUrl, { params });
  }

  updateAlertStatus(alertUuid: string, request: { status: string; reviewComment: string; actionTaken: string }): Observable<FraudAlertResponse> {
    return this.http.put<FraudAlertResponse>(`${this.apiUrl}/${alertUuid}/status`, request);
  }

  getAlertByUuid(alertUuid: string): Observable<FraudAlertResponse> {
    return this.http.get<FraudAlertResponse>(`${this.apiUrl}/${alertUuid}`);
  }

  getRecentAnomalies(limit: number = 8): Observable<FraudAlertResponse[]> {
    const teamContext = this.authService.getTeamContext();
    let params = new HttpParams().set('limit', limit.toString());
    if (teamContext.managerId) params = params.set('managerId', teamContext.managerId.toString());

    return this.http.get<FraudAlertResponse[]>(`${this.apiUrl}/recent`, { params });
  }

  /**
   * Get fraud locations (postal codes with fraud counts and coordinates).
   * @param days Number of days to look back (default 7)
   */
  getFraudLocations(days: number = 7): Observable<FraudLocationResponse[]> {
    const teamContext = this.authService.getTeamContext();
    let params = new HttpParams().set('days', days.toString());
    if (teamContext.managerId) params = params.set('managerId', teamContext.managerId.toString());

    return this.http.get<FraudLocationResponse[]>(`${this.apiUrl}/locations`, { params });
  }

  /**
 * Remove assignment for a list of alerts.
 */
  unassignAlerts(alertUuids: string[]): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/unassign`, alertUuids);
  }

  /**
   * Assign a list of alerts (by UUID) to a specific analyst.
   */
  assignAlerts(alertUuids: string[], analystId: number): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/assign`, { alertUuids, analystId });
  }

  /**
   * Get all analysts belonging to the connected manager.
   */
  getMyAnalysts(): Observable<AnalystInfo[]> {
    return this.http.get<AnalystInfo[]>(`${this.apiUrl}/my-analysts`);
  }

  countAlertsByAnalyst(analystId: number): Observable<number> {
    return this.http.get<number>(`${this.apiUrl}/count/analyst/${analystId}`);
  }
}