import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class AccountService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/accounts`;

  getTotalCount(): Observable<number> {
    return this.http.get<number>(`${this.apiUrl}/count`);
  }

  getActiveCount(): Observable<number> {
    return this.http.get<number>(`${this.apiUrl}/active/count`);
  }

  getTotalBalance(): Observable<number> {
    return this.http.get<number>(`${this.apiUrl}/balance/total`);
  }

  getAccounts(page: number = 0, size: number = 20): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}?page=${page}&size=${size}`);
  }

  getAccountById(id: number): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/${id}`);
  }
}