import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface ClientSummary {
  id: number;
  firstName: string;
  lastName: string;
  fullName: string;
  accountCount: number;
}

export interface AccountSummary {
  id: number;
  accountNumber: string;
  accountType: string;
  balance: number;
  currency: string;
  isActive: boolean;
  riskLevel: string;
}

export interface ClientDetail extends ClientSummary {
  dateOfBirth: string;
  nationalId: string;
  accounts: AccountSummary[];
  createdAt: string;
  updatedAt: string;
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
export class ClientService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/clients`;

  getClients(page: number = 0, size: number = 20): Observable<PageResponse<ClientSummary>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<PageResponse<ClientSummary>>(this.apiUrl, { params });
  }

  getClientById(id: number): Observable<ClientDetail> {
    return this.http.get<ClientDetail>(`${this.apiUrl}/${id}`);
  }

  searchClients(query: string, accountCount?: number, page: number = 0, size: number = 20): Observable<PageResponse<ClientSummary>> {
    let params = new HttpParams()
      .set('search', query)
      .set('page', page.toString())
      .set('size', size.toString());

    if (accountCount !== undefined && accountCount !== null) {
      params = params.set('accountCount', accountCount.toString());
    }

    return this.http.get<PageResponse<ClientSummary>>(`${this.apiUrl}/search`, { params });
  }

  getTotalCount(): Observable<number> {
    return this.http.get<number>(`${this.apiUrl}/count`);
  }
}