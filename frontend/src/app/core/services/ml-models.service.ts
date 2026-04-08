import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import { 
  Dataset, 
  TrainingJobRequest, 
  TrainingJobResponse,
  ModelMetricsResponse 
} from '../models/ml-models.models';

@Injectable({
  providedIn: 'root'
})
export class MlModelsService {
  private apiUrl = environment.apiUrl;
  private authService = inject(AuthService);

  constructor(private http: HttpClient) {}

  getProcessedDatasets(): Observable<Dataset[]> {
    const teamContext = this.authService.getTeamContext();
    let url = `${this.apiUrl}/datasets/training?status=PROCESSED`;
    if (teamContext.managerId) {
      url += `&managerId=${teamContext.managerId}`;
    }
    return this.http.get<Dataset[]>(url);
  }

  getDataset(datasetUuid: string): Observable<Dataset> {
    return this.http.get<Dataset>(`${this.apiUrl}/datasets/${datasetUuid}`);
  }

  getDatasetFeatures(datasetUuid: string): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/datasets/${datasetUuid}/analysis`);
  }

  trainRandomForest(request: TrainingJobRequest): Observable<TrainingJobResponse> {
    return this.http.post<TrainingJobResponse>(
      `${this.apiUrl}/api/v1/fraud-detection/models/training/random-forest`, 
      request
    );
  }

  getModelMetrics(modelUuid: string): Observable<ModelMetricsResponse> {
    return this.http.get<ModelMetricsResponse>(
      `${this.apiUrl}/api/v1/fraud-detection/models/training/${modelUuid}/metrics`
    );
  }
}