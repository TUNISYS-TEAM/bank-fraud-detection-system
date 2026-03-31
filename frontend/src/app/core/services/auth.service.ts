import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError, of, timer, BehaviorSubject } from 'rxjs';
import { catchError, tap, map, switchMap, shareReplay, finalize, delay } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import {
  LoginRequest,
  UserInfo,
  AuthUser,
  UserRole,
  Permission,
  RoleResponse
} from '../models/auth.model';

// Shape returned by GET /api/auth/me
interface MeResponse {
  sub: string;
  email: string;
  email_verified?: boolean;
  name: string;
  preferred_username: string;
  given_name?: string;
  family_name?: string;
  realm_access?: {
    roles: string[];
  };
}

// Shape returned by login endpoint
interface LoginResponse {
  username: string;
  expires_in?: number;
}

// Shape returned by refresh endpoint
interface RefreshResponse {
  message?: string;
  expires_in?: number;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private currentUser: AuthUser | null = null;
  private sessionId: string | null = null;
  private tokenExpiresAt: number = 0;
  private refreshInProgress: boolean = false;
  private refreshSubject: Observable<any> | null = null;
  private http = inject(HttpClient);


  // ──────────────────────────────────────────────────────────────────────────
  // LOGIN
  // ──────────────────────────────────────────────────────────────────────────
  login(credentials: LoginRequest): Observable<{ user: AuthUser }> {
    return this.http.post<any>(
      `${environment.apiUrl}/auth/login`,
      credentials,
      {
        withCredentials: true,
        observe: 'response'
      }
    ).pipe(
      tap(response => {
        const body = response.body;
        if (body?.expires_in) {
          this.tokenExpiresAt = Date.now() + body.expires_in * 1000;
        }
      }),
      map(response => response.body),
      switchMap((body) => of(body).pipe(
        delay(500),
        switchMap(() => this.loadUserProfile(credentials.email))
      )),
      map(user => ({ user })),
      catchError(err => this.handleError(err))
    );
  }

  // ──────────────────────────────────────────────────────────────────────────
  // LOAD USER PROFILE
  // ──────────────────────────────────────────────────────────────────────────
  loadUserProfile(email?: string, force: boolean = false): Observable<AuthUser> {
    if (!force && this.currentUser && (!email || this.currentUser.email === email)) {
      return of(this.currentUser);
    }

    return this.http.get<MeResponse>(
      `${environment.apiUrl}/auth/me`,
      { withCredentials: true }
    ).pipe(
      switchMap(me => {
        const resolvedEmail = email || me.email || me.preferred_username;
        const roles = me.realm_access?.roles || [];
        const role = this.extractRoleFromList(roles);

        // Load permissions dynamically from backend via existing endpoint
        return this.http.get<RoleResponse>(
          `${environment.apiUrl}/roles/name/${role}`,
          { withCredentials: true }
        ).pipe(
          map(roleResponse => {
            const user: AuthUser = {
              id: me.sub,
              email: resolvedEmail,
              name: me.name || resolvedEmail,
              firstName: me.given_name || resolvedEmail.split('@')[0],
              lastName: me.family_name || '',
              role: role,
              permissions: roleResponse.permissions || [],
              userId: 0,
              roleId: roleResponse.id
            };

            this.currentUser = user;
            return user;
          })
        );
      }),
      catchError(err => {
        console.error('Failed to load user profile:', err);
        return throwError(() => err);
      })
    );
  }

  // ──────────────────────────────────────────────────────────────────────────
  // REFRESH TOKEN
  // ──────────────────────────────────────────────────────────────────────────
  refreshToken(): Observable<any> {
    if (this.refreshInProgress && this.refreshSubject) {
      return this.refreshSubject;
    }

    if (!this.currentUser) {
      return of({ success: true, message: 'No session to refresh' });
    }

    this.refreshInProgress = true;

    this.refreshSubject = this.http.post<RefreshResponse>(
      `${environment.apiUrl}/auth/refresh`,
      {},
      {
        withCredentials: true,
        observe: 'response'
      }
    ).pipe(
      tap(response => {
        const body = response.body;
        if (body?.expires_in) {
          this.tokenExpiresAt = Date.now() + body.expires_in * 1000;
        }
      }),
      map(response => response.body),
      finalize(() => {
        this.refreshInProgress = false;
        this.refreshSubject = null;
      }),
      catchError(err => {
        console.error('Refresh failed:', err);
        this.clearLocalState();
        return of({ success: false, error: 'Refresh failed' });
      }),
      shareReplay(1)
    );

    return this.refreshSubject;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // CHECK SESSION
  // ──────────────────────────────────────────────────────────────────────────
  checkSession(): Observable<boolean> {
    return this.http.get<MeResponse>(
      `${environment.apiUrl}/auth/me`,
      { withCredentials: true }
    ).pipe(
      map(() => true),
      catchError(() => of(false))
    );
  }

  // ──────────────────────────────────────────────────────────────────────────
  // AUTO REFRESH SETUP
  // ──────────────────────────────────────────────────────────────────────────
  setupAutoRefresh(): void {
    timer(60000, 60000).subscribe(() => {
      if (this.isTokenExpired() && this.isLoggedIn()) {
        this.refreshToken().subscribe();
      }
    });
  }

  // ──────────────────────────────────────────────────────────────────────────
  // LOGOUT
  // ──────────────────────────────────────────────────────────────────────────
  logout(): Observable<void> {
    return this.http.post<void>(
      `${environment.apiUrl}/auth/logout`,
      {},
      {
        withCredentials: true,
        observe: 'response'
      }
    ).pipe(
      tap(() => this.clearLocalState()),
      map(() => void 0),
      catchError(err => {
        console.error('Logout error:', err);
        this.clearLocalState();
        return of(void 0);
      })
    );
  }

  // ──────────────────────────────────────────────────────────────────────────
  // STATE ACCESSORS
  // ──────────────────────────────────────────────────────────────────────────
  isLoggedIn(): boolean {
    return this.currentUser !== null;
  }

  isTokenExpired(): boolean {
    if (this.tokenExpiresAt === 0) return false;
    return Date.now() >= this.tokenExpiresAt - 60_000;
  }

  getCurrentUser(): AuthUser | null {
    return this.currentUser;
  }

  getSessionId(): string | null {
    return this.sessionId;
  }

  getUserInfoFromToken(): UserInfo | null {
    if (!this.currentUser) return null;

    return {
      sub: this.currentUser.id,
      email: this.currentUser.email,
      email_verified: true,
      name: this.currentUser.name,
      preferred_username: this.currentUser.email,
      given_name: this.currentUser.firstName,
      family_name: this.currentUser.lastName,
      realm_access: {
        roles: [this.currentUser.role]
      }
    };
  }

  fetchCsrfToken(): Observable<void> {
    return this.http.get<void>(
      `${environment.apiUrl}/auth/csrf`,
      {
        withCredentials: true,
        observe: 'response'
      }
    ).pipe(
      map(() => void 0),
      catchError(err => {
        console.error('Error fetching CSRF token:', err);
        return of(void 0);
      })
    );
  }

  getToken(): string | null {
    return null;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Private helpers
  // ──────────────────────────────────────────────────────────────────────────
  private extractRoleFromList(roles: string[]): UserRole {
    const upper = roles.map(r => r.toUpperCase());
    if (upper.includes('ADMIN')) return 'ADMIN';
    if (upper.includes('MANAGER')) return 'MANAGER';
    if (upper.includes('ANALYST')) return 'ANALYST';
    return 'ANALYST';
  }



  private getRoleIdFromRole(role: UserRole): number {
    switch(role) {
      case 'ADMIN': return 1;
      case 'MANAGER': return 2;
      case 'ANALYST': return 3;
      default: return 3;
    }
  }

  private clearLocalState(): void {
    this.currentUser = null;
    this.sessionId = null;
    this.tokenExpiresAt = 0;
    this.refreshInProgress = false;
    this.refreshSubject = null;
  }

  private handleError(error: any): Observable<never> {
    let message = 'Une erreur est survenue';

    if (error.error instanceof ErrorEvent) {
      message = `Erreur: ${error.error.message}`;
    } else if (error instanceof HttpErrorResponse) {
      switch (error.status) {
        case 401:
          message = 'Email ou mot de passe incorrect';
          if (error.url?.includes('/auth/login')) {
            this.clearLocalState();
          }
          break;
        case 403:
          message = 'Accès refusé';
          break;
        case 404:
          message = 'Service non trouvé';
          break;
        case 0:
          message = 'Impossible de se connecter au serveur';
          break;
        default:
          message = error.error?.message || error.message || 'Erreur inconnue';
      }
    }

    console.error('Auth error:', { status: error.status, message });
    return throwError(() => new Error(message));
  }

  // Deprecated methods kept for backward compatibility
  getUserByEmail(email: string): Observable<any> {
    return of(null);
  }

  getPermissionsByRoleId(roleId: number): Observable<Permission[]> {
    if (!this.currentUser) return of([]);
    return of(this.currentUser.permissions);
  }

  getRoleById(roleId: number): Observable<any> {
    return of({ id: roleId, name: this.currentUser?.role || 'ANALYST' });
  }
}