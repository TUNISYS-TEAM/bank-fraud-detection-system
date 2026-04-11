import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SimulationRequest, SimulationResponse, ModelSummaryResponse } from '../models/ml-models.models';

@Injectable({
  providedIn: 'root'
})
export class SimulationService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/v1/simulation`;

  runSimulation(request: SimulationRequest): Observable<SimulationResponse> {
    return this.http.post<SimulationResponse>(`${this.apiUrl}/run`, request);
  }

  getAvailableModels(): Observable<ModelSummaryResponse[]> {
    return this.http.get<ModelSummaryResponse[]>(`${this.apiUrl}/models`);
  }
}