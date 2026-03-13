import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export enum DatasetStatus {
    ACTIVE = 'ACTIVE',
    PROCESSED = 'PROCESSED', 
    INACTIVE = 'INACTIVE'
}

export interface Dataset {
    datasetUuid: string;
    datasetCode: string;
    datasetName: string;
    fileSizeMb: number;
    rowCount: number;
    columnCount: number;
    status: DatasetStatus;
    importedAt: string;
}

export interface DatasetPreviewResponse {
    datasetUuid: string;
    datasetName: string;
    columns: string[];
    rows: Record<string, string>[];
    returnedRows: number;
    limit: number;
}

export interface ApiResponse<T> {
    content: T[];
    totalElements: number;
    totalPages: number;
    size: number;
    number: number;
}

@Injectable({
    providedIn: 'root'
})
export class DatasetService {
    private http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/datasets`;

    list(params: { q?: string; status?: DatasetStatus; page?: number; size?: number }): Observable<ApiResponse<Dataset>> {
        let httpParams = new HttpParams();
        if (params.q) httpParams = httpParams.set('q', params.q);
        if (params.status) httpParams = httpParams.set('status', params.status);
        if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
        if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());

        return this.http.get<ApiResponse<Dataset>>(this.apiUrl, { params: httpParams });
    }

    get(uuid: string): Observable<Dataset> {
        return this.http.get<Dataset>(`${this.apiUrl}/${uuid}`);
    }

    preview(uuid: string, limit: number = 20): Observable<DatasetPreviewResponse> {
        return this.http.get<DatasetPreviewResponse>(`${this.apiUrl}/${uuid}/preview`, {
            params: new HttpParams().set('limit', limit.toString())
        });
    }

    upload(file: File, datasetName: string): Observable<Dataset> {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('datasetName', datasetName);
        return this.http.post<Dataset>(this.apiUrl, formData);
    }

    delete(uuid: string): Observable<void> {
        return this.http.delete<void>(`${this.apiUrl}/${uuid}`);
    }

    update(uuid: string, data: { datasetName?: string; status?: DatasetStatus }): Observable<Dataset> {
        return this.http.put<Dataset>(`${this.apiUrl}/${uuid}`, data);
    }

    download(uuid: string): Observable<Blob> {
        return this.http.get(`${this.apiUrl}/${uuid}/download`, { responseType: 'blob' });
    }

    // Méthodes spécifiques pour la gestion des statuts
    activateDataset(uuid: string): Observable<Dataset> {
        return this.update(uuid, { status: DatasetStatus.ACTIVE });
    }

    deactivateDataset(uuid: string): Observable<Dataset> {
        return this.update(uuid, { status: DatasetStatus.PROCESSED }); // Désactiver = retour à PROCESSED
    }

    markAsProcessed(uuid: string): Observable<Dataset> {
        return this.update(uuid, { status: DatasetStatus.PROCESSED });
    }

    // Méthode pour nettoyer un dataset (appel au backend Python)
    cleanDataset(uuid: string): Observable<any> {
        return this.http.post(`${this.apiUrl}/${uuid}/clean`, {});
    }

    getCleaningStatus(uuid: string): Observable<any> {
        return this.http.get(`${this.apiUrl}/${uuid}/clean/status`);
    }
}