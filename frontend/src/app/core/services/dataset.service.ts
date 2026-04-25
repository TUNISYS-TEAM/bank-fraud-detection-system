import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, Subject } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';

export enum DatasetStatus {
    PROCESSED = 'PROCESSED',
    CLEANED = 'CLEANED'
}

export enum DatasetType {
    TRANSACTIONS_TRAIN = 'TRANSACTIONS_TRAIN',
    TRANSACTIONS_TRAIN_ENRICHED = 'TRANSACTIONS_TRAIN_ENRICHED',
    TRANSACTIONS_VAL = 'TRANSACTIONS_VAL',
    CLIENTS = 'CLIENTS',
    ACCOUNTS = 'ACCOUNTS'
}

export interface Dataset {
    datasetUuid: string;
    datasetCode: string;
    datasetName: string;
    fileSizeMb: number;
    rowCount: number;
    columnCount: number;
    status: DatasetStatus | null;
    type: DatasetType;
    importedAt: string;
    visibility: boolean;
    qualified: boolean;
    assignedManagerId?: number;
    assignedManagerName?: string;
    assignedManagerEmail?: string;
}

export interface DatasetPreviewResponse {
    datasetUuid: string;
    datasetName: string;
    columns: string[];
    rows: Record<string, string>[];
    returnedRows: number;
    limit: number;
}

export interface ColumnInfo {
    name: string;
    type: string; // NUMERIC, CATEGORICAL, DATE, BINARY, TEXT
    uniqueCount: number;
    nullRatio: number;
    stats: Record<string, any>;
    isTarget: boolean;
}

export interface DatasetAnalysisResponse {
    datasetUuid: string;
    datasetName: string;
    status: string;
    columns: ColumnInfo[];
    statistics: Record<string, any>;
    suggestedFeatures: string[];
    suggestedTarget: string;
    sampleRows?: number;
    preview?: Record<string, any>[];
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
    private authService = inject(AuthService);
    private readonly apiUrl = `${environment.apiUrl}/datasets`;
    private readonly userUrl = `${environment.apiUrl}/users`;

    list(params: { q?: string; status?: DatasetStatus; types?: DatasetType | DatasetType[]; type?: DatasetType; visibility?: boolean; qualified?: boolean; managerId?: number; page?: number; size?: number }): Observable<ApiResponse<Dataset>> {
        const teamContext = this.authService.getTeamContext();
        let httpParams = new HttpParams();
        
        const finalManagerId = params.managerId || teamContext.managerId;
        
        if (params.q) httpParams = httpParams.set('q', params.q);
        if (params.status) httpParams = httpParams.set('status', params.status);
        if (params.type) httpParams = httpParams.set('type', params.type);
        
        if (params.types) {
            if (Array.isArray(params.types)) {
                params.types.forEach(t => {
                    httpParams = httpParams.append('types', t);
                });
            } else {
                httpParams = httpParams.set('types', params.types);
            }
        }
        
        if (params.visibility !== undefined) httpParams = httpParams.set('visibility', params.visibility.toString());
        if (params.qualified !== undefined) httpParams = httpParams.set('qualified', params.qualified.toString());
        if (finalManagerId !== undefined) httpParams = httpParams.set('managerId', finalManagerId.toString());
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

    analyze(uuid: string): Observable<DatasetAnalysisResponse> {
        return this.http.post<DatasetAnalysisResponse>(`${this.apiUrl}/${uuid}/analyze`, {});
    }

    getAnalysis(uuid: string): Observable<DatasetAnalysisResponse> {
        return this.http.get<DatasetAnalysisResponse>(`${this.apiUrl}/${uuid}/analysis`);
    }

    upload(file: File, datasetName: string, type: DatasetType): Observable<Dataset> {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('datasetName', datasetName);
        formData.append('type', type);
        return this.http.post<Dataset>(this.apiUrl, formData);
    }

    delete(uuid: string): Observable<void> {
        return this.http.delete<void>(`${this.apiUrl}/${uuid}`);
    }

    update(uuid: string, data: { datasetName?: string; status?: DatasetStatus; visibility?: boolean; assignedManagerId?: number }): Observable<Dataset> {
        return this.http.put<Dataset>(`${this.apiUrl}/${uuid}`, data);
    }

    download(uuid: string): Observable<Blob> {
        return this.http.get(`${this.apiUrl}/${uuid}/download`, { responseType: 'blob' });
    }

    getManagers(): Observable<any[]> {
        return this.http.get<any[]>(`${this.userUrl}/available-managers`);
    }

    count(): Observable<number> {
        return this.http.get<number>(`${this.apiUrl}/count`);
    }

    getTotalSize(): Observable<number> {
        return this.http.get<number>(`${this.apiUrl}/total-size`);
    }

    getLatestImportDate(): Observable<string> {
        return this.http.get<string>(`${this.apiUrl}/latest-import`);
    }

    cloneForTraining(uuid: string): Observable<Dataset> {
        return this.http.post<Dataset>(`${this.apiUrl}/${uuid}/clone-for-training`, {});
    }

    checkIfEnriched(uuid: string): Observable<boolean> {
        return this.http.get<boolean>(`${this.apiUrl}/${uuid}/is-enriched`);
    }
}