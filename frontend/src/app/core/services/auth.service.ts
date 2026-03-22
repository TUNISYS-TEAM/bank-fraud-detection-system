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

// Shape returned by user profile endpoint (from Keycloak)
interface UserInfoResponse {
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

@Injectable({ providedIn: 'root' })
export class AuthService {
  private currentUser: AuthUser | null = null;
  private sessionId: string | null = null;
  private tokenExpiresAt: number = 0;
  private refreshInProgress: boolean = false;
  private refreshSubject: Observable<any> | null = null;
  private http = inject(HttpClient);
  
  // Cache for user profile
  private userProfileCache = new BehaviorSubject<AuthUser | null>(null);

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
      switchMap(() => this.loadUserProfile().pipe(delay(500))),
      map(user => ({ user })),
      catchError(err => this.handleError(err))
    );
  }

  // ──────────────────────────────────────────────────────────────────────────
  // LOAD USER PROFILE FROM KEYCLOAK
  // ──────────────────────────────────────────────────────────────────────────
  loadUserProfile(): Observable<AuthUser> {
    // Return cached user if available
    if (this.currentUser) {
      return of(this.currentUser);
    }

    return this.http.get<UserInfoResponse>(
      `${environment.apiUrl}/auth/me`,
      { withCredentials: true }
    ).pipe(
      tap(me => {
        console.debug('User profile loaded:', me);
      }),
      map(me => this.mapFromKeycloak(me)),
      tap(user => {
        this.currentUser = user;
        this.userProfileCache.next(user);
        // After loading user, load their permissions based on role
        this.loadUserPermissions().subscribe();
      }),
      catchError(err => {
        console.error('Failed to load user profile:', err);
        return throwError(() => err);
      })
    );
  }

  /**
   * Map Keycloak response to AuthUser
   */
  private mapFromKeycloak(me: UserInfoResponse): AuthUser {
    const roles = me.realm_access?.roles || [];
    const role = this.extractRoleFromList(roles);
    const roleId = this.getRoleIdFromRole(role);
    
    return {
      id: me.sub,
      email: me.email || me.preferred_username,
      name: me.name || me.preferred_username,
      firstName: me.given_name || me.preferred_username.split('@')[0],
      lastName: me.family_name || '',
      role: role,
      roleId: roleId,
      permissions: [],
      userId: 0
    };
  }

  // ──────────────────────────────────────────────────────────────────────────
  // ROLE & PERMISSION METHODS
  // ──────────────────────────────────────────────────────────────────────────
  
  /**
   * Get role by ID - this should return the role with its permissions
   */
  getRoleById(roleId: number): Observable<RoleResponse> {
    return this.http.get<RoleResponse>(
      `${environment.apiUrl}/roles/${roleId}`,
      { withCredentials: true }
    ).pipe(
      catchError(err => {
        console.error(`Error fetching role ${roleId}:`, err);
        return throwError(() => err);
      })
    );
  }

  /**
   * Get role by name
   */
  getRoleByName(name: string): Observable<RoleResponse> {
    return this.http.get<RoleResponse>(
      `${environment.apiUrl}/api/roles/name/${name}`,
      { withCredentials: true }
    ).pipe(
      catchError(err => {
        console.error(`Error fetching role ${name}:`, err);
        return throwError(() => err);
      })
    );
  }

  /**
   * Get permissions for a specific role
   */
  getPermissionsByRoleId(roleId: number): Observable<Permission[]> {
    return this.getRoleById(roleId).pipe(
      map(role => {
        console.log(`Role data for ${roleId}:`, role);
        // The role should already have its permissions
        return role.permissions || [];
      }),
      catchError(err => {
        console.error(`Error fetching permissions for role ${roleId}:`, err);
        return of([]);
      })
    );
  }

  /**
   * Load current user's permissions based on their role
   */
  loadUserPermissions(): Observable<Permission[]> {
    if (!this.currentUser?.roleId) {
      console.log('No roleId found for user:', this.currentUser);
      return of([]);
    }
    
    console.log('Loading permissions for roleId:', this.currentUser.roleId);
    
    return this.getPermissionsByRoleId(this.currentUser.roleId).pipe(
      tap(permissions => {
        console.log('Permissions loaded for user:', permissions);
        if (this.currentUser) {
          this.currentUser.permissions = permissions;
          this.userProfileCache.next(this.currentUser);
        }
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
    return this.http.get<UserInfoResponse>(
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
  // PERMISSION CHECK METHODS
  // ──────────────────────────────────────────────────────────────────────────
  
  /**
   * Check if the current user has a specific permission
   */
  hasPermission(permissionName: string): boolean {
    if (!this.currentUser?.permissions) return false;
    return this.currentUser.permissions.some(p => p.name === permissionName);
  }

  /**
   * Check if the current user has any of the given permissions
   */
  hasAnyPermission(permissionNames: string[]): boolean {
    if (!this.currentUser?.permissions) return false;
    return permissionNames.some(name => 
      this.currentUser!.permissions.some(p => p.name === name)
    );
  }

  /**
   * Check if the current user has all of the given permissions
   */
  hasAllPermissions(permissionNames: string[]): boolean {
    if (!this.currentUser?.permissions) return false;
    return permissionNames.every(name => 
      this.currentUser!.permissions.some(p => p.name === name)
    );
  }

  /**
   * Get all permissions for the current user
   */
  getUserPermissions(): Permission[] {
    return this.currentUser?.permissions || [];
  }

  /**
   * Check if user can access a specific route/action
   */
  canAccess(action: string): boolean {
    if (!this.currentUser?.permissions) return false;
    return this.currentUser.permissions.some(p => 
      p.action === action || p.name === action
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

  getUserRole(): UserRole | null {
    return this.currentUser?.role || null;
  }

  getUserRoleId(): number | null {
    return this.currentUser?.roleId || null;
  }

  getUserId(): number | null {
    return this.currentUser?.userId || null;
  }

  getUserEmail(): string | null {
    return this.currentUser?.email || null;
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
    this.userProfileCache.next(null);
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

    console.error('Auth error:', error);
    return throwError(() => new Error(message));
  }

  // Legacy method for backward compatibility
  getUserByEmail(email: string): Observable<any> {
    return of(null);
  }
}