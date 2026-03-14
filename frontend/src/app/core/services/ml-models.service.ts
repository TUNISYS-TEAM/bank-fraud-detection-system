import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
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

  constructor(private http: HttpClient) {}

  getProcessedDatasets(): Observable<Dataset[]> {
    // Get all training datasets (TRANSACTIONS_TRAIN) with status PROCESSED
    return this.http.get<Dataset[]>(`${this.apiUrl}/datasets/training?status=PROCESSED`);
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