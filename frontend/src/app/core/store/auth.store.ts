import { signalStore, withState, withComputed, withMethods, patchState } from '@ngrx/signals';
import { computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, of } from 'rxjs';
import { tap, map, switchMap, catchError } from 'rxjs/operators';
import { AuthService } from '../services/auth.service';
import { AuthUser, LoginRequest, MenuItem, UserRole } from '../models/auth.model';

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
  'MANAGE_DATASETS': 'database',
  'MANAGE_CLIENTS_ACCOUNTS': 'users'
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
  'MANAGE_DATASETS': 'Datasets',
  'MANAGE_CLIENTS_ACCOUNTS': 'Clients & Comptes'
};

const PERMISSION_ORDER: Record<string, number> = {
  'ACCESS_OVERVIEW': 5,
  'ACCESS_IMPORT_DATA': 10,
  'ACCESS_ML_MODELS': 20,
  'ACCESS_ALERTS': 30,
  'ACCESS_PERFORMANCE': 40,
  'ACCESS_SIMULATION': 50,
  'ACCESS_MESSAGES': 60,
  'ACCESS_ADMINISTRATION': 70,
  'MANAGE_USERS': 71,
  'MANAGE_ROLES': 72,
  'MANAGE_SESSIONS': 73,
  'MANAGE_LOGS': 74,
  'MANAGE_DATASETS': 75,
  'MANAGE_CLIENTS_ACCOUNTS': 75.5,
  'MANAGE_SETTINGS': 76,
  'MANAGE_ML_MODELS': 111,
  'MANAGE_ALERTS': 112,
  'IMPORT_DATA': 113,
  'RUN_SIMULATION': 114
};

const PARENT_PAGES: Record<string, { parentRoute: string; relativeRoute: string }> = {
  'MANAGE_USERS': { parentRoute: '/dashboard/administration', relativeRoute: 'users' },
  'MANAGE_ROLES': { parentRoute: '/dashboard/administration', relativeRoute: 'roles' },
  'MANAGE_SESSIONS': { parentRoute: '/dashboard/administration', relativeRoute: 'sessions' },
  'MANAGE_LOGS': { parentRoute: '/dashboard/administration', relativeRoute: 'logs' },
  'MANAGE_SETTINGS': { parentRoute: '/dashboard/administration', relativeRoute: 'settings' },
  'MANAGE_DATASETS': { parentRoute: '/dashboard/administration', relativeRoute: 'datasets' },
  'MANAGE_CLIENTS_ACCOUNTS': { parentRoute: '/dashboard/administration', relativeRoute: 'clients-accounts' },
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

      for (const perm of userPermissions) {
        if (perm.name === 'ACCESS_OVERVIEW' || perm.name === 'ACCESS_MESSAGES') continue;
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
        .filter(item => item.permissionName.startsWith('ACCESS_'))
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
    };
  }),

  withMethods((store, authService = inject(AuthService), router = inject(Router)) => ({

    // ──────────────────────────────────────────────────────────────────────
    // LOGIN
    // ──────────────────────────────────────────────────────────────────────
    login(credentials: LoginRequest): void {
      if (store.loading()) {
        return;
      }

      patchState(store, { loading: true, error: null });

      authService.login(credentials).subscribe({
        next: ({ user }) => {
          patchState(store, {
            user,
            isAuthenticated: true,
            loading: false,
            error: null
          });
          router.navigate(['/dashboard/overview']);
        },
        error: (error) => {
          const msg = error.message || 'Erreur de connexion';
          patchState(store, { loading: false, error: msg });

          setTimeout(() => {
            if (store.error() === msg) {
              patchState(store, { error: null });
            }
          }, 5000);
        }
      });
    },

    // ──────────────────────────────────────────────────────────────────────
    // RESTORE SESSION
    // ──────────────────────────────────────────────────────────────────────
    // In auth.store.ts - update the restoreSession method:

    restoreSession(): Observable<boolean> {
      return authService.checkSession().pipe(
        switchMap((isValid) => {
          if (isValid) {
            patchState(store, { loading: true });
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
              catchError(() => {
                patchState(store, { loading: false });
                return of(false);
              })
            );
          } else {
            patchState(store, { loading: false });
            return of(false);
          }
        }),
        catchError(() => {
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
            // Even if CSRF fails, try to restore session
            this.restoreSession().subscribe(() => {
              authService.setupAutoRefresh();
              resolve();
            });
          }
        });
      });
    },

    // ──────────────────────────────────────────────────────────────────────
    // LOGOUT
    // ──────────────────────────────────────────────────────────────────────
    logout(): void {
      patchState(store, { loading: true });

      authService.logout().subscribe({
        next: () => {
          this.clearSession();
        },
        error: () => {
          this.clearSession();
        }
      });
    },

    clearSession(): void {
      patchState(store, initialAuthState);
      router.navigate(['/auth/login']);
    },

    clearError(): void {
      patchState(store, { error: null });
    },

    // ──────────────────────────────────────────────────────────────────────
    // PERMISSIONS
    // ──────────────────────────────────────────────────────────────────────
    hasPermission(permissionName: string): boolean {
      if (permissionName === 'ACCESS_OVERVIEW' || permissionName === 'ACCESS_MESSAGES') return true;
      return store.permissionNames().includes(permissionName);
    },

    hasAnyPermission(permissionNames: string[]): boolean {
      if (permissionNames.includes('ACCESS_OVERVIEW') || permissionNames.includes('ACCESS_MESSAGES')) return true;
      const userPerms = store.permissionNames();
      return permissionNames.some(name => userPerms.includes(name));
    },

    hasAllPermissions(permissionNames: string[]): boolean {
      const filtered = permissionNames.filter(
        p => p !== 'ACCESS_OVERVIEW' && p !== 'ACCESS_MESSAGES'
      );
      if (filtered.length === 0) return true;
      const userPerms = store.permissionNames();
      return filtered.every(name => userPerms.includes(name));
    },

    setLoading(loading: boolean): void {
      patchState(store, { loading });
    },

    // ──────────────────────────────────────────────────────────────────────
    // REFRESH USER PROFILE - UPDATED TO USE NEW SERVICE METHODS
    // ──────────────────────────────────────────────────────────────────────
    refreshUserProfile(): void {
      const email = store.userEmail();
      if (!email) {
        return;
      }

      patchState(store, { loading: true });

      // Use the new simplified methods from AuthService
      authService.loadUserProfile(email).subscribe({
        next: (user) => {
          patchState(store, {
            user,
            loading: false
          });
        },
        error: (err) => {
          console.error('Error refreshing profile:', err);
          patchState(store, { loading: false });
        }
      });
    },

    getTabsForPage(pageRoute: string): MenuItem[] {
      return store.tabsByParentPage()[pageRoute] || [];
    }
  }))
);