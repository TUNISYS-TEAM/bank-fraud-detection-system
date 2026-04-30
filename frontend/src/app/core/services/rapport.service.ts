import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Rapport, GenerateRapportRequest } from '../models/rapport.model';

@Injectable({
  providedIn: 'root'
})
export class RapportService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/rapports`;

  generateRapport(request: GenerateRapportRequest): Observable<Rapport> {
    return this.http.post<Rapport>(`${this.baseUrl}/generate`, request);
  }

  getReceivedRapports(page: number = 0, size: number = 10): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/received?page=${page}&size=${size}`);
  }

  getGeneratedRapports(page: number = 0, size: number = 10): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/generated?page=${page}&size=${size}`);
  }

  getRapport(uuid: string): Observable<Rapport> {
    return this.http.get<Rapport>(`${this.baseUrl}/${uuid}`);
  }

  markAsRead(uuid: string): Observable<void> {
    return this.http.patch<void>(`${this.baseUrl}/${uuid}/read`, {});
  }

  getUnreadCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.baseUrl}/unread-count`);
  }

  shareRapport(uuid: string, recipientIds: number[]): Observable<Rapport> {
    return this.http.patch<Rapport>(`${this.baseUrl}/${uuid}/share`, { recipientIds });
  }
}