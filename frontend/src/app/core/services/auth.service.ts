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

interface LoginResponse {
  username: string;
  expires_in?: number;
}

interface RefreshResponse {
  message?: string;
  expires_in?: number;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private currentUser: AuthUser | null = null;
  private tokenExpiresAt: number = 0;
  private refreshInProgress: boolean = false;
  private refreshSubject: Observable<any> | null = null;

  private userSubject = new BehaviorSubject<AuthUser | null>(null);
  public user$ = this.userSubject.asObservable();

  private http = inject(HttpClient);

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
      switchMap(() => of({}).pipe(
        delay(500),
        switchMap(() => this.loadUserProfile(credentials.email))
      )),
      map(user => ({ user })),
      catchError(err => this.handleError(err))
    );
  }

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

        // Chain multiple backend calls to get full user profile
        return this.http.get<RoleResponse>(
          `${environment.apiUrl}/roles/name/${role}`,
          { withCredentials: true }
        ).pipe(
          switchMap(roleResponse => {
            // Get DB user info
            return this.http.get<any>(
              `${environment.apiUrl}/users/me`,
              { withCredentials: true }
            ).pipe(
              switchMap(dbUser => {
                // Get unread notifications count
                return this.http.get<{ unreadCount: number }>(
                  `${environment.apiUrl}/notifications/unread/count`,
                  { withCredentials: true }
                ).pipe(
                  map(notifResponse => {
                    const user: AuthUser = {
                      id: me.sub,
                      email: resolvedEmail,
                      name: me.name || resolvedEmail,
                      firstName: me.given_name || resolvedEmail.split('@')[0],
                      lastName: me.family_name || '',
                      role: role,
                      permissions: roleResponse.permissions || [],
                      userId: dbUser.id,
                      roleId: roleResponse.id,
                      managerId: dbUser.managerId,
                    };

                    this.currentUser = user;
                    this.userSubject.next(user);
                    return user;
                  })
                );
              })
            );
          })
        );
      }),
      catchError(err => {
        console.error('Failed to load user profile:', err);
        this.userSubject.next(null);
        return throwError(() => err);
      })
    );
  }

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

  checkSession(): Observable<boolean> {
    return this.http.get<MeResponse>(`${environment.apiUrl}/auth/me`, { withCredentials: true })
      .pipe(
        map(() => true),
        catchError((error) => {
          if (error.status !== 401) {
            console.error('Session check failed:', error);
          }
          return of(false);
        })
      );
  }

  setupAutoRefresh(): void {
    timer(60000, 60000).subscribe(() => {
      if (this.isTokenExpired() && this.isLoggedIn()) {
        this.refreshToken().subscribe();
      }
    });
  }

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

  getTeamContext(): { managerId?: number } {
    if (!this.currentUser) return {};

    if (this.roleHasName(this.currentUser.role, 'ADMIN')) {
      return {}; // No filtering for ADMIN
    }

    if (this.roleHasName(this.currentUser.role, 'MANAGER')) {
      return { managerId: this.currentUser.userId }; // Filter by self as manager
    }

    if (this.roleHasName(this.currentUser.role, 'ANALYST')) {
      return { managerId: this.currentUser.managerId }; // Filter by assigned manager
    }

    return {};
  }

  private roleHasName(role: string, name: string): boolean {
    return role.toUpperCase() === name.toUpperCase();
  }

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

  getToken(): string | null {
    return null;
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
      catchError(() => of(void 0))
    );
  }

  private extractRoleFromList(roles: string[]): UserRole {
    const upper = roles.map(r => r.toUpperCase());
    if (upper.includes('ADMIN')) return 'ADMIN';
    if (upper.includes('MANAGER')) return 'MANAGER';
    return 'ANALYST';
  }

  private clearLocalState(): void {
    this.currentUser = null;
    this.userSubject.next(null);
    this.tokenExpiresAt = 0;
    this.refreshInProgress = false;
    this.refreshSubject = null;
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    // For 403 EMAIL_NOT_VERIFIED, pass the error through without logging
    if (error.status === 403 && error.error?.error === 'EMAIL_NOT_VERIFIED') {
      return throwError(() => error);
    }

    // Log other errors
    console.error('Auth error:', {
      status: error.status,
      message: error.message,
      error: error.error
    });

    return throwError(() => error);
  }

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