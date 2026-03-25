import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface SystemMetrics {
  uptime: number;
  requestsPerSecond: number;
  cpuUsage: number;
  memoryUsage: number;
  responseTime: number;
  activeConnections: number;
  diskUsage: number;
  threadCount: number;
  jvmVersion: string;
  osName: string;
  osArch: string;
  availableProcessors: number;
}

export interface SystemHealth {
  healthScore: number;
  status: string;
  timestamp: string;
  message: string;
}

export interface MetricsHistory {
  requestsPerSecond: Array<{ time: string; value: number }>;
  responseTime: Array<{ time: string; value: number }>;
  cpuUsage: Array<{ time: string; value: number }>;
  memoryUsage: Array<{ time: string; value: number }>;
}

@Injectable({ providedIn: 'root' })
export class SystemMetricsService {
  private baseUrl = `${environment.apiUrl}/system`;
  
  constructor(private http: HttpClient) {}
  
  getSystemMetrics(): Observable<SystemMetrics> {
    return this.http.get<SystemMetrics>(`${this.baseUrl}/metrics`);
  }
  
  getSystemHealth(): Observable<SystemHealth> {
    return this.http.get<SystemHealth>(`${this.baseUrl}/health`);
  }
  
  getMetricsHistory(minutes: number = 60): Observable<MetricsHistory> {
    return this.http.get<MetricsHistory>(`${this.baseUrl}/metrics/history`, {
      params: { minutes: minutes.toString() }
    });
  }
  
  getUptime(): Observable<{ uptime: number }> {
    return this.http.get<{ uptime: number }>(`${this.baseUrl}/uptime`);
  }
  
  getRequestsPerSecond(): Observable<{ requestsPerSecond: number }> {
    return this.http.get<{ requestsPerSecond: number }>(`${this.baseUrl}/metrics/requests`);
  }
  
  getCpuUsage(): Observable<{ cpuUsage: number }> {
    return this.http.get<{ cpuUsage: number }>(`${this.baseUrl}/metrics/cpu`);
  }
  
  getMemoryUsage(): Observable<{ memoryUsage: number }> {
    return this.http.get<{ memoryUsage: number }>(`${this.baseUrl}/metrics/memory`);
  }
  
  getResponseTime(): Observable<{ responseTime: number }> {
    return this.http.get<{ responseTime: number }>(`${this.baseUrl}/metrics/response-time`);
  }
  
  getActiveConnections(): Observable<{ activeConnections: number }> {
    return this.http.get<{ activeConnections: number }>(`${this.baseUrl}/metrics/connections`);
  }
}