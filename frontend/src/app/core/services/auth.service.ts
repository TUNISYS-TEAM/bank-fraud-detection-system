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
  Permission
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

  // Simple role to permission mapping
  private rolePermissions: Record<string, Permission[]> = {
    'ADMIN': [
      { id: 1, name: 'ACCESS_OVERVIEW', description: 'Access Dashboard', action: '/dashboard/overview' },
      { id: 2, name: 'ACCESS_ADMINISTRATION', description: 'Access Administration', action: '/dashboard/administration' },
      { id: 3, name: 'ACCESS_IMPORT_DATA', description: 'Access Import Data', action: '/dashboard/import-data' },
      { id: 4, name: 'ACCESS_ML_MODELS', description: 'Access ML Models', action: '/dashboard/ml-models' },
      { id: 5, name: 'ACCESS_ALERTS', description: 'Access Alerts', action: '/dashboard/alerts' },
      { id: 6, name: 'ACCESS_PERFORMANCE', description: 'Access Performance', action: '/dashboard/performance' },
      { id: 7, name: 'ACCESS_SIMULATION', description: 'Access Simulation', action: '/dashboard/simulation' },
      { id: 8, name: 'ACCESS_MESSAGES', description: 'Access Messages', action: '/dashboard/messages' },
      { id: 9, name: 'MANAGE_USERS', description: 'Manage Users', action: '/dashboard/administration/users' },
      { id: 10, name: 'MANAGE_ROLES', description: 'Manage Roles', action: '/dashboard/administration/roles' },
      { id: 11, name: 'MANAGE_SESSIONS', description: 'Manage Sessions', action: '/dashboard/administration/sessions' },
      { id: 12, name: 'MANAGE_ML_MODELS', description: 'Manage ML Models', action: '/dashboard/ml-models/manage' },
      { id: 13, name: 'MANAGE_ALERTS', description: 'Manage Alerts', action: '/dashboard/alerts/manage' },
      { id: 14, name: 'IMPORT_DATA', description: 'Import Data', action: '/dashboard/import-data/advanced' },
      { id: 15, name: 'RUN_SIMULATION', description: 'Run Simulation', action: '/dashboard/simulation/run' },
      { id: 16, name: 'MANAGE_LOGS', description: 'Manage Logs', action: '/dashboard/administration/logs' },
      { id: 17, name: 'MANAGE_SETTINGS', description: 'Manage Settings', action: '/dashboard/administration/settings' },
      { id: 18, name: 'MANAGE_DATASETS', description: 'Manage Datasets', action: '/dashboard/administration/datasets' },
      { id: 19, name: 'MANAGE_CLIENTS_ACCOUNTS', description: 'Gérer les clients et leurs comptes (consultation, détails)', action: '/dashboard/administration/clients-accounts' },
      { id: 20, name: 'ACCESS_TRAINING_DATA', description: 'Accès à la gestion des datasets d\'entraînement', action: '/dashboard/training-data' }
    ],
    'MANAGER': [
      { id: 1, name: 'ACCESS_OVERVIEW', description: 'Access Dashboard', action: '/dashboard/overview' },
      { id: 2, name: 'ACCESS_IMPORT_DATA', description: 'Access Import Data', action: '/dashboard/import-data' },
      { id: 3, name: 'ACCESS_ML_MODELS', description: 'Access ML Models', action: '/dashboard/ml-models' },
      { id: 4, name: 'ACCESS_ALERTS', description: 'Access Alerts', action: '/dashboard/alerts' },
      { id: 5, name: 'ACCESS_PERFORMANCE', description: 'Access Performance', action: '/dashboard/performance' },
      { id: 6, name: 'ACCESS_SIMULATION', description: 'Access Simulation', action: '/dashboard/simulation' },
      { id: 7, name: 'ACCESS_MESSAGES', description: 'Access Messages', action: '/dashboard/messages' },
      { id: 8, name: 'IMPORT_DATA', description: 'Import Data', action: '/dashboard/import-data/advanced' },
      { id: 9, name: 'RUN_SIMULATION', description: 'Run Simulation', action: '/dashboard/simulation/run' }
    ],
    'ANALYST': [
      { id: 1, name: 'ACCESS_OVERVIEW', description: 'Access Dashboard', action: '/dashboard/overview' },
      { id: 2, name: 'ACCESS_IMPORT_DATA', description: 'Access Import Data', action: '/dashboard/import-data' },
      { id: 3, name: 'ACCESS_ML_MODELS', description: 'Access ML Models', action: '/dashboard/ml-models' },
      { id: 4, name: 'ACCESS_ALERTS', description: 'Access Alerts', action: '/dashboard/alerts' },
      { id: 5, name: 'ACCESS_PERFORMANCE', description: 'Access Performance', action: '/dashboard/performance' },
      { id: 6, name: 'ACCESS_SIMULATION', description: 'Access Simulation', action: '/dashboard/simulation' },
      { id: 7, name: 'ACCESS_MESSAGES', description: 'Access Messages', action: '/dashboard/messages' }
    ]
  };

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
  loadUserProfile(email?: string): Observable<AuthUser> {
    if (this.currentUser && (!email || this.currentUser.email === email)) {
      return of(this.currentUser);
    }

    return this.http.get<MeResponse>(
      `${environment.apiUrl}/auth/me`,
      { withCredentials: true }
    ).pipe(
      map(me => {
        const resolvedEmail = email || me.email || me.preferred_username;
        const roles = me.realm_access?.roles || [];
        const role = this.extractRoleFromList(roles);
        const permissions = this.getPermissionsForRole(role);
        
        const user: AuthUser = {
          id: me.sub,
          email: resolvedEmail,
          name: me.name || resolvedEmail,
          firstName: me.given_name || resolvedEmail.split('@')[0],
          lastName: me.family_name || '',
          role: role,
          permissions: permissions,
          userId: 0,
          roleId: this.getRoleIdFromRole(role)
        };
        
        this.currentUser = user;
        return user;
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

  private getPermissionsForRole(role: UserRole): Permission[] {
    return this.rolePermissions[role] || this.rolePermissions['ANALYST'];
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
    return of(this.getPermissionsForRole(this.currentUser.role));
  }

  getRoleById(roleId: number): Observable<any> {
    return of({ id: roleId, name: this.currentUser?.role || 'ANALYST' });
  }
}