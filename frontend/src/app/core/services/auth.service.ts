import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError, of } from 'rxjs';
import { catchError, tap, map, switchMap } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import {
  LoginRequest,
  LoginResponse,
  RefreshTokenRequest,
  UserInfo,
  AuthUser,
  UserRole,
  Permission
} from '../models/auth.model';

export interface UserResponse {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  roleId: number;
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly TOKEN_KEY = 'access_token';
  private readonly REFRESH_TOKEN_KEY = 'refresh_token';
  private readonly SESSION_ID_KEY = 'session_id';
  private readonly TOKEN_EXPIRY_KEY = 'token_expiry';

  private http = inject(HttpClient);

  login(credentials: LoginRequest): Observable<{ response: LoginResponse; user: AuthUser }> {
    return this.http.post<LoginResponse>(`${environment.apiUrl}/auth/login`, credentials)
      .pipe(
        tap(response => this.storeTokens(response)),
        switchMap(response => {
          const tokenData = this.parseJwt(response.access_token);
          const role = this.extractRole(tokenData);

          const sessionId = this.extractSessionId(tokenData);
          if (sessionId) {
            localStorage.setItem(this.SESSION_ID_KEY, sessionId);
          }

          return this.getUserByEmail(credentials.email).pipe(
            switchMap(userData => {
              return this.getPermissionsByRoleId(userData.roleId).pipe(
                map(permissions => {
                  const user: AuthUser = {
                    id: tokenData.sub || '',
                    email: credentials.email,
                    name: tokenData.name || credentials.email,
                    firstName: tokenData.given_name || '',
                    lastName: tokenData.family_name || '',
                    role: role,
                    permissions: permissions,
                    userId: userData.id,
                    roleId: userData.roleId
                  };
                  return { response, user };
                })
              );
            })
          );
        }),
        catchError(this.handleError)
      );
  }

  getUserByEmail(email: string): Observable<{ id: number; roleId: number }> {
    const url = `${environment.apiUrl}/users/email/${email}`;
    return this.http.get<UserResponse>(url).pipe(
      map(user => ({
        id: user.id,
        roleId: user.roleId
      })),
      catchError(error => {
        console.error('Error fetching user by email:', error);
        return throwError(() => new Error('User not found'));
      })
    );
  }

  getPermissionsByRoleId(roleId: number): Observable<Permission[]> {
    const url = `${environment.apiUrl}/roles/${roleId}/permissions`;
    return this.http.get<Permission[]>(url).pipe(
      catchError(error => {
        console.error('Error fetching permissions by roleId:', error);
        return of([]);
      })
    );
  }

  refreshToken(): Observable<LoginResponse> {
    const refreshToken = this.getRefreshToken();
    if (!refreshToken) {
      return throwError(() => new Error('No refresh token available'));
    }

    const payload: RefreshTokenRequest = { refresh_token: refreshToken };
    return this.http.post<LoginResponse>(`${environment.apiUrl}/auth/refresh`, payload)
      .pipe(
        tap(response => {
          this.storeTokens(response);
          const tokenData = this.parseJwt(response.access_token);
          const sessionId = this.extractSessionId(tokenData);
          if (sessionId) {
            localStorage.setItem(this.SESSION_ID_KEY, sessionId);
          }
        }),
        catchError(error => {
          this.logout();
          return throwError(() => error);
        })
      );
  }

  logout(): Observable<void> {
    const refreshToken = this.getRefreshToken();
    const sessionId = this.getSessionId();
    const accessToken = this.getToken();

    const payload: any = {};
    if (refreshToken) payload.refresh_token = refreshToken;
    if (sessionId) payload.session_id = sessionId;
    if (accessToken) payload.access_token = accessToken;

    return this.http.post<void>(`${environment.apiUrl}/auth/logout`, payload)
      .pipe(
        tap(() => {
          this.clearTokens();
          console.log('Logout successful');
        }),
        catchError(error => {
          console.error('Logout error:', error);
          this.clearTokens();
          return of(void 0);
        })
      );
  }

  private extractRole(tokenData: any): UserRole {
    const roles = tokenData?.realm_access?.roles || [];
    if (roles.includes('ADMIN')) return 'ADMIN';
    if (roles.includes('MANAGER')) return 'MANAGER';
    if (roles.includes('ANALYST')) return 'ANALYST';
    return 'ANALYST';
  }

  private extractSessionId(tokenData: any): string | null {
    return tokenData?.session_state || tokenData?.sid || null;
  }

  private storeTokens(response: LoginResponse): void {
    const expiryTime = Date.now() + (response.expires_in * 1000);
    localStorage.setItem(this.TOKEN_KEY, response.access_token);
    localStorage.setItem(this.REFRESH_TOKEN_KEY, response.refresh_token);
    localStorage.setItem(this.TOKEN_EXPIRY_KEY, expiryTime.toString());
    if (response.session_state) {
      localStorage.setItem(this.SESSION_ID_KEY, response.session_state);
    }
  }

  getToken(): string | null {
    return localStorage.getItem(this.TOKEN_KEY);
  }

  getRefreshToken(): string | null {
    return localStorage.getItem(this.REFRESH_TOKEN_KEY);
  }

  getSessionId(): string | null {
    return localStorage.getItem(this.SESSION_ID_KEY);
  }

  isLoggedIn(): boolean {
    const token = this.getToken();
    if (!token) return false;
    return !this.isTokenExpired();
  }

  isTokenExpired(): boolean {
    const expiry = localStorage.getItem(this.TOKEN_EXPIRY_KEY);
    if (!expiry) return true;
    const expiryTime = parseInt(expiry, 10);
    return Date.now() >= (expiryTime - 60000);
  }

  parseJwt(token: string): any {
    try {
      const base64Url = token.split('.')[1];
      const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
      const jsonPayload = decodeURIComponent(
        atob(base64)
          .split('')
          .map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
          .join('')
      );
      return JSON.parse(jsonPayload);
    } catch (error) {
      console.error('Error parsing JWT:', error);
      return null;
    }
  }

  getUserInfoFromToken(): UserInfo | null {
    const token = this.getToken();
    return token ? this.parseJwt(token) : null;
  }

  private clearTokens(): void {
    localStorage.removeItem(this.TOKEN_KEY);
    localStorage.removeItem(this.REFRESH_TOKEN_KEY);
    localStorage.removeItem(this.TOKEN_EXPIRY_KEY);
    localStorage.removeItem(this.SESSION_ID_KEY);
  }

  private handleError(error: any): Observable<never> {
    let errorMessage = 'Une erreur est survenue';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Erreur: ${error.error.message}`;
    } else {
      if (error.status === 401) errorMessage = 'Email ou mot de passe incorrect';
      else if (error.status === 403) errorMessage = 'Accès refusé';
      else if (error.status === 0) errorMessage = 'Impossible de se connecter au serveur';
      else if (error.error?.message) errorMessage = error.error.message;
    }
    console.error('Auth error:', error);
    return throwError(() => new Error(errorMessage));
  }
}