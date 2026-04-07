import { signalStore, withState, withComputed, withMethods, patchState } from '@ngrx/signals';
import { computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { tap, map, switchMap, catchError } from 'rxjs/operators';
import { AuthService } from '../services/auth.service';
import { AuthUser, LoginRequest, MenuItem, UserRole } from '../models/auth.model';
import { HttpErrorResponse } from '@angular/common/http';

const PERMISSION_ICONS: Record<string, string> = {
  'ACCESS_OVERVIEW': 'grid',
  'ACCESS_ADMINISTRATION': 'settings',
  'ACCESS_IMPORT_DATA': 'upload',
  'ACCESS_ML_MODELS': 'activity',
  'ACCESS_ALERTS': 'bell',
  'ACCESS_PERFORMANCE': 'bar-chart-2',
  'ACCESS_SIMULATION': 'play-circle',
  'ACCESS_MESSAGES': 'mail',
  'MANAGE_USERS': 'users',
  'MANAGE_ROLES': 'key',
  'MANAGE_SESSIONS': 'clock',
  'MANAGE_ML_MODELS': 'cpu',
  'MANAGE_ALERTS': 'alert-octagon',
  'IMPORT_DATA': 'download',
  'RUN_SIMULATION': 'play',
  'MANAGE_LOGS': 'file-text',
  'MANAGE_SETTINGS': 'sliders',
  'ACCESS_DATASETS': 'database',
  'ACCESS_CLIENTS_ACCOUNTS': 'users',
  'ACCESS_ANALYZED_TRANSACTIONS': 'activity',
  'ACCESS_TRAINING_DATA': 'database',
  'ACCESS_PROFILE': 'user',
  'ACCESS_HISTORY': 'clock'   
};

const PERMISSION_LABELS: Record<string, string> = {
  'ACCESS_OVERVIEW': 'Dashboard',
  'ACCESS_ADMINISTRATION': 'Administration',
  'ACCESS_IMPORT_DATA': 'Import Données',
  'ACCESS_ML_MODELS': 'Modèles ML',
  'ACCESS_ALERTS': 'Alertes',
  'ACCESS_PERFORMANCE': 'Performances',
  'ACCESS_SIMULATION': 'Simulation',
  'ACCESS_MESSAGES': 'Messages',
  'MANAGE_USERS': 'Utilisateurs',
  'MANAGE_ROLES': 'Rôles',
  'MANAGE_SESSIONS': 'Sessions',
  'MANAGE_ML_MODELS': 'Configuration Modèles',
  'MANAGE_ALERTS': 'Configuration Alertes',
  'IMPORT_DATA': 'Import Avancé',
  'RUN_SIMULATION': 'Lancer Simulation',
  'MANAGE_LOGS': 'Journaux',
  'MANAGE_SETTINGS': 'Paramètres',
  'ACCESS_DATASETS': 'Datasets',
  'ACCESS_CLIENTS_ACCOUNTS': 'Clients & Comptes',
  'ACCESS_ANALYZED_TRANSACTIONS': 'Transactions Analysées',
  'ACCESS_TRAINING_DATA': 'Données d\'Entraînement',
  'ACCESS_PROFILE': 'Mon Profil',
  'ACCESS_HISTORY': 'Historique'  
};

const PERMISSION_ORDER: Record<string, number> = {
  'ACCESS_OVERVIEW': 5,
  'ACCESS_IMPORT_DATA': 10,
  'ACCESS_ML_MODELS': 20,
  'ACCESS_TRAINING_DATA': 30,
  'ACCESS_ALERTS': 40,
  'ACCESS_PERFORMANCE': 50,
  'ACCESS_SIMULATION': 55,
  'ACCESS_MESSAGES': 60,
  'ACCESS_DATASETS': 61,
  'ACCESS_CLIENTS_ACCOUNTS': 62,
  'ACCESS_ANALYZED_TRANSACTIONS': 63,
  'ACCESS_ADMINISTRATION': 70,
  'MANAGE_USERS': 71,
  'MANAGE_ROLES': 72,
  'MANAGE_SESSIONS': 73,
  'MANAGE_LOGS': 74,
  'MANAGE_SETTINGS': 76,
  'MANAGE_ML_MODELS': 111,
  'MANAGE_ALERTS': 112,
  'IMPORT_DATA': 113,
  'RUN_SIMULATION': 114,
  'ACCESS_PROFILE': 200,
  'ACCESS_HISTORY': 65    
};

const PARENT_PAGES: Record<string, { parentRoute: string; relativeRoute: string }> = {
  'MANAGE_USERS': { parentRoute: '/dashboard/administration', relativeRoute: 'users' },
  'MANAGE_ROLES': { parentRoute: '/dashboard/administration', relativeRoute: 'roles' },
  'MANAGE_SESSIONS': { parentRoute: '/dashboard/administration', relativeRoute: 'sessions' },
  'MANAGE_LOGS': { parentRoute: '/dashboard/administration', relativeRoute: 'logs' },
  'MANAGE_SETTINGS': { parentRoute: '/dashboard/administration', relativeRoute: 'settings' },
  'MANAGE_ML_MODELS': { parentRoute: '/dashboard/ml-models', relativeRoute: 'manage' },
  'MANAGE_ALERTS': { parentRoute: '/dashboard/alerts', relativeRoute: 'manage' },
  'IMPORT_DATA': { parentRoute: '/dashboard/import-data', relativeRoute: 'advanced' },
  'RUN_SIMULATION': { parentRoute: '/dashboard/simulation', relativeRoute: 'run' }
};

export interface AuthState {
  user: AuthUser | null;
  isAuthenticated: boolean;
  loading: boolean;
  error: string | null;
}

const initialAuthState: AuthState = {
  user: null,
  isAuthenticated: false,
  loading: false,
  error: null
};

export const AuthStore = signalStore(
  { providedIn: 'root' },
  withState(initialAuthState),

  withComputed((store) => {
    const permissionNames = computed(() =>
      store.user()?.permissions.map(p => p.name) ?? []
    );

    const permissions = computed(() =>
      store.user()?.permissions ?? []
    );

    const menuItems = computed(() => {
      const userPermissions = permissions();
      const items: MenuItem[] = [];

      items.push({
        id: 0,
        label: PERMISSION_LABELS['ACCESS_OVERVIEW'],
        route: '/dashboard/overview',
        icon: PERMISSION_ICONS['ACCESS_OVERVIEW'],
        order: PERMISSION_ORDER['ACCESS_OVERVIEW'],
        permissionName: 'ACCESS_OVERVIEW'
      });

      items.push({
        id: 999,
        label: PERMISSION_LABELS['ACCESS_MESSAGES'],
        route: '/dashboard/messages',
        icon: PERMISSION_ICONS['ACCESS_MESSAGES'],
        order: PERMISSION_ORDER['ACCESS_MESSAGES'],
        permissionName: 'ACCESS_MESSAGES'
      });

     
      items.push({
        id: 1001,
        label: PERMISSION_LABELS['ACCESS_HISTORY'],
        route: '/dashboard/history',
        icon: PERMISSION_ICONS['ACCESS_HISTORY'],
        order: PERMISSION_ORDER['ACCESS_HISTORY'],
        permissionName: 'ACCESS_HISTORY'
      });

      items.push({
        id: 1000,
        label: PERMISSION_LABELS['ACCESS_PROFILE'],
        route: '/dashboard/profile',
        icon: PERMISSION_ICONS['ACCESS_PROFILE'],
        order: PERMISSION_ORDER['ACCESS_PROFILE'],
        permissionName: 'ACCESS_PROFILE'
      });

      for (const perm of userPermissions) {
        if (perm.name === 'ACCESS_OVERVIEW' || perm.name === 'ACCESS_MESSAGES' || perm.name === 'ACCESS_HISTORY' || perm.name === 'ACCESS_PROFILE') continue;
        if (perm.action) {
          items.push({
            id: perm.id,
            label: PERMISSION_LABELS[perm.name] || perm.description || perm.name,
            route: perm.action,
            icon: PERMISSION_ICONS[perm.name] || 'circle',
            order: PERMISSION_ORDER[perm.name] || 999,
            permissionName: perm.name
          });
        }
      }

      return items.sort((a, b) => a.order - b.order);
    });

    const mainPages = computed(() =>
      menuItems()
        .filter(item => item.permissionName.startsWith('ACCESS_') && item.permissionName !== 'ACCESS_HISTORY')
        .sort((a, b) => a.order - b.order)
    );

    const tabsByParentPage = computed(() => {
      const tabs: Record<string, MenuItem[]> = {};
      for (const item of menuItems()) {
        const parentConfig = PARENT_PAGES[item.permissionName];
        if (parentConfig) {
          if (!tabs[parentConfig.parentRoute]) tabs[parentConfig.parentRoute] = [];
          tabs[parentConfig.parentRoute].push({ ...item, route: parentConfig.relativeRoute });
        }
      }
      for (const parent in tabs) {
        tabs[parent].sort((a, b) => a.order - b.order);
      }
      return tabs;
    });

    return {
      isLoggedIn: computed(() => store.isAuthenticated()),
      userId: computed(() => store.user()?.userId ?? 0),
      userName: computed(() => store.user()?.name ?? ''),
      userRole: computed(() => store.user()?.role ?? 'ANALYST'),
      userEmail: computed(() => store.user()?.email ?? ''),
      userFirstName: computed(() => store.user()?.firstName ?? ''),
      userLastName: computed(() => store.user()?.lastName ?? ''),
      userRoleId: computed(() => store.user()?.roleId),

      userRoleLabel: computed(() => {
        switch (store.user()?.role) {
          case 'ADMIN': return 'Administrateur';
          case 'MANAGER': return 'Responsable du Risque';
          case 'ANALYST': return 'Analyste de Fraude';
          default: return 'Utilisateur';
        }
      }),

      isAdmin: computed(() => store.user()?.role === 'ADMIN'),
      isManager: computed(() => store.user()?.role === 'MANAGER'),
      isAnalyst: computed(() => store.user()?.role === 'ANALYST'),
      isLoading: computed(() => store.loading()),
      errorMessage: computed(() => store.error()),

      permissions,
      permissionNames,
      menuItems,
      mainPages,
      tabsByParentPage,

      administrationTabs: computed(() => tabsByParentPage()['/dashboard/administration'] || []),
      mlModelsTabs: computed(() => tabsByParentPage()['/dashboard/ml-models'] || []),
      alertsTabs: computed(() => tabsByParentPage()['/dashboard/alerts'] || []),
      importDataTabs: computed(() => tabsByParentPage()['/dashboard/import-data'] || []),
      simulationTabs: computed(() => tabsByParentPage()['/dashboard/simulation'] || []),
      clientsTabs: computed(() => [
        { id: 101, label: 'Liste des Clients', route: 'liste-details', icon: 'list', order: 1, permissionName: 'ACCESS_CLIENTS_ACCOUNTS' },
        { id: 102, label: 'Stats Clients', route: 'clients', icon: 'users', order: 2, permissionName: 'ACCESS_CLIENTS_ACCOUNTS' },
        { id: 103, label: 'Stats Comptes', route: 'accounts', icon: 'credit-card', order: 3, permissionName: 'ACCESS_CLIENTS_ACCOUNTS' }
      ]),
    };
  }),

  withMethods((store, authService = inject(AuthService), router = inject(Router)) => ({

    login(credentials: LoginRequest): Observable<{ user: AuthUser }> {
      if (store.loading()) {
        return throwError(() => new Error('Login already in progress'));
      }

      patchState(store, { loading: true, error: null });

      return authService.login(credentials).pipe(
        tap(({ user }) => {
          patchState(store, {
            user,
            isAuthenticated: true,
            loading: false,
            error: null
          });
          router.navigate(['/dashboard/overview']);
        }),
        catchError((error: HttpErrorResponse) => {
          // Clear loading state for all errors
          patchState(store, { loading: false });

          // For email not verified, just pass through without setting error message
          if (error.status === 403 && error.error?.error === 'EMAIL_NOT_VERIFIED') {
            return throwError(() => error);
          }

          // For other errors, set error message
          const msg = error.error?.message || error.message || 'Erreur de connexion';
          patchState(store, { error: msg });

          setTimeout(() => {
            if (store.error() === msg) {
              patchState(store, { error: null });
            }
          }, 5000);

          return throwError(() => error);
        })
      );
    },

    restoreSession(): Observable<boolean> {
      patchState(store, { loading: true });

      return authService.checkSession().pipe(
        switchMap((isValid) => {
          if (isValid) {
            return authService.loadUserProfile().pipe(
              tap((user) => {
                patchState(store, {
                  user,
                  isAuthenticated: true,
                  loading: false,
                  error: null
                });
              }),
              map(() => true),
              catchError((error) => {
                console.error('Failed to load user profile after valid session:', error);
                patchState(store, { loading: false });
                return of(false);
              })
            );
          } else {
            patchState(store, { loading: false });
            return of(false);
          }
        }),
        catchError((error) => {
          console.error('Session restoration failed:', error);
          patchState(store, { loading: false });
          return of(false);
        })
      );
    },

    init(): Promise<void> {
      return new Promise((resolve) => {
        authService.fetchCsrfToken().subscribe({
          next: () => {
            this.restoreSession().subscribe(() => {
              authService.setupAutoRefresh();
              resolve();
            });
          },
          error: () => {
            this.restoreSession().subscribe(() => {
              authService.setupAutoRefresh();
              resolve();
            });
          }
        });
      });
    },

    logout(): void {
      patchState(store, { loading: true });

      authService.logout().subscribe({
        next: () => this.clearSession(),
        error: () => this.clearSession()
      });
    },

    clearSession(): void {
      patchState(store, initialAuthState);
      router.navigate(['/auth/login']);
    },

    clearError(): void {
      patchState(store, { error: null });
    },

    hasPermission(permissionName: string): boolean {
      if (permissionName === 'ACCESS_OVERVIEW' || permissionName === 'ACCESS_MESSAGES' || permissionName === 'ACCESS_HISTORY' || permissionName === 'ACCESS_PROFILE') return true;
      return store.permissionNames().includes(permissionName);
    },

    hasAnyPermission(permissionNames: string[]): boolean {
      if (permissionNames.includes('ACCESS_OVERVIEW') || permissionNames.includes('ACCESS_MESSAGES') || permissionNames.includes('ACCESS_HISTORY') || permissionNames.includes('ACCESS_PROFILE')) return true;
      const userPerms = store.permissionNames();
      return permissionNames.some(name => userPerms.includes(name));
    },

    hasAllPermissions(permissionNames: string[]): boolean {
      const filtered = permissionNames.filter(
        p => p !== 'ACCESS_OVERVIEW' && p !== 'ACCESS_MESSAGES' && p !== 'ACCESS_HISTORY' && p !== 'ACCESS_PROFILE'
      );
      if (filtered.length === 0) return true;
      const userPerms = store.permissionNames();
      return filtered.every(name => userPerms.includes(name));
    },

    setLoading(loading: boolean): void {
      patchState(store, { loading });
    },

    refreshUserProfile(): void {
      patchState(store, { loading: true });

      authService.loadUserProfile().subscribe({
        next: (user) => {
          patchState(store, { user, loading: false });
        },
        error: (err) => {
          console.error('Error refreshing profile:', err);
          patchState(store, { loading: false });
        }
      });
    },

    updateUser(userData: Partial<AuthUser>): void {
      const currentUser = store.user();
      if (currentUser) {
        const updatedUser = { ...currentUser, ...userData };

        // Ensure the full name is updated if first or last name changes
        if (userData.firstName || userData.lastName) {
          updatedUser.name = `${updatedUser.firstName} ${updatedUser.lastName}`;
        }

        patchState(store, {
          user: updatedUser
        });
      }
    },

    getTabsForPage(pageRoute: string): MenuItem[] {
      return store.tabsByParentPage()[pageRoute] || [];
    }
  }))
);