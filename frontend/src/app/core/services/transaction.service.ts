import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface TransactionSummary {
  id: number;
  transactionUuid: string;
  amount: number;
  currency: string;
  transactionDate: string;
  merchantCategory: string;
  channel: string;
  status: string;
  isFraud: boolean;
  fraudScore?: number;
  accountNumber: string;
  clientName: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

@Injectable({
  providedIn: 'root'
})
export class TransactionService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/transactions`;

  getAnalyzedTransactions(page: number = 0, size: number = 20): Observable<PageResponse<TransactionSummary>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    
    return this.http.get<PageResponse<TransactionSummary>>(`${this.apiUrl}/analyzed`, { params });
  }

  getTransactionStats(): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/stats/time-series`);
  }

  getGlobalAnalyzedStats(): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/stats/analyzed-global`);
  }
}