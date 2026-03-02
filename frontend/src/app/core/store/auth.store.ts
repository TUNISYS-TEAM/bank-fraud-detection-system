import { signalStore, withState, withComputed, withMethods, patchState } from '@ngrx/signals';
import { computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { AuthUser, LoginRequest, MenuItem } from '../models/auth.model';

const PERMISSION_ICONS: Record<string, string> = {
  // Pages principales - icônes uniques
  'ACCESS_OVERVIEW': 'grid',           
  'ACCESS_ADMINISTRATION': 'settings',     
  'ACCESS_IMPORT_DATA': 'upload',           
  'ACCESS_ML_MODELS': 'activity',           
  'ACCESS_ALERTS': 'bell',                  
  'ACCESS_PERFORMANCE': 'bar-chart-2',      
  'ACCESS_SIMULATION': 'play-circle',       
  
  // Actions de gestion - icônes uniques
  'MANAGE_USERS': 'users',              
  'MANAGE_ROLES': 'key',                  
  'MANAGE_SESSIONS': 'clock',               
  'MANAGE_ML_MODELS': 'cpu',               
  'MANAGE_ALERTS': 'alert-octagon',     
  'IMPORT_DATA': 'download',                 
  'RUN_SIMULATION': 'play',                 
  'MANAGE_LOGS': 'file-text',                 
  'MANAGE_SETTINGS': 'sliders',              
  'MANAGE_DATASETS': 'database'              
};

const PERMISSION_LABELS: Record<string, string> = {
  // Pages principales
  'ACCESS_OVERVIEW': 'Dashboard',           
  'ACCESS_ADMINISTRATION': 'Administration',
  'ACCESS_IMPORT_DATA': 'Import Données',
  'ACCESS_ML_MODELS': 'Modèles ML',
  'ACCESS_ALERTS': 'Alertes',
  'ACCESS_PERFORMANCE': 'Performances',
  'ACCESS_SIMULATION': 'Simulation',

  // Actions de gestion
  'MANAGE_USERS': 'Utilisateurs',
  'MANAGE_ROLES': 'Rôles',
  'MANAGE_SESSIONS': 'Sessions',
  'MANAGE_ML_MODELS': 'Configuration Modèles',
  'MANAGE_ALERTS': 'Configuration Alertes',
  'IMPORT_DATA': 'Import Avancé',
  'RUN_SIMULATION': 'Lancer Simulation',
  'MANAGE_LOGS': 'Journaux',
  'MANAGE_SETTINGS': 'Paramètres',
  'MANAGE_DATASETS': 'Datasets'
};

const PERMISSION_ORDER: Record<string, number> = {
  // Pages principales
  'ACCESS_OVERVIEW': 5,                     
  'ACCESS_ADMINISTRATION': 10,
  'ACCESS_IMPORT_DATA': 20,
  'ACCESS_ML_MODELS': 30,
  'ACCESS_ALERTS': 40,
  'ACCESS_PERFORMANCE': 50,
  'ACCESS_SIMULATION': 60,

  // Actions dans Administration
  'MANAGE_USERS': 71,
  'MANAGE_ROLES': 72,
  'MANAGE_SESSIONS': 73,
  'MANAGE_LOGS': 74,
  'MANAGE_DATASETS': 75,
  'MANAGE_SETTINGS': 76,

  // Actions avancées
  'MANAGE_ML_MODELS': 111,
  'MANAGE_ALERTS': 112,
  'IMPORT_DATA': 113,
  'RUN_SIMULATION': 114
};

// Configuration des pages parentes pour les tabs avec routes RELATIVES
const PARENT_PAGES: Record<string, { parentRoute: string; relativeRoute: string }> = {
  // Administration
  'MANAGE_USERS': { parentRoute: '/dashboard/administration', relativeRoute: 'users' },
  'MANAGE_ROLES': { parentRoute: '/dashboard/administration', relativeRoute: 'roles' },
  'MANAGE_SESSIONS': { parentRoute: '/dashboard/administration', relativeRoute: 'sessions' },
  'MANAGE_LOGS': { parentRoute: '/dashboard/administration', relativeRoute: 'logs' },
  'MANAGE_SETTINGS': { parentRoute: '/dashboard/administration', relativeRoute: 'settings' },
  'MANAGE_DATASETS': { parentRoute: '/dashboard/administration', relativeRoute: 'datasets' },

  // Modèles ML
  'MANAGE_ML_MODELS': { parentRoute: '/dashboard/ml-models', relativeRoute: 'manage' },

  // Alertes
  'MANAGE_ALERTS': { parentRoute: '/dashboard/alerts', relativeRoute: 'manage' },

  // Import
  'IMPORT_DATA': { parentRoute: '/dashboard/import-data', relativeRoute: 'advanced' },

  // Simulation
  'RUN_SIMULATION': { parentRoute: '/dashboard/simulation', relativeRoute: 'run' }
};

export interface AuthState {
  user: AuthUser | null;
  token: string | null;
  isAuthenticated: boolean;
  loading: boolean;
  error: string | null;
}

const AUTH_STORAGE_KEY = 'fraud_auth_state';
const initialAuthState: AuthState = {
  user: null,
  token: null,
  isAuthenticated: false,
  loading: false,
  error: null
};

function loadState(): AuthState {
  try {
    const stored = localStorage.getItem(AUTH_STORAGE_KEY);
    if (!stored) return initialAuthState;
    const state = JSON.parse(stored);
    const expiry = localStorage.getItem('token_expiry');
    if (state.isAuthenticated && expiry && Date.now() >= parseInt(expiry, 10)) {
      localStorage.removeItem(AUTH_STORAGE_KEY);
      return initialAuthState;
    }
    return state;
  } catch (error) {
    return initialAuthState;
  }
}

function saveState(state: AuthState): void {
  try {
    localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(state));
  } catch (error) {
    // Silently handle error
  }
}

export const AuthStore = signalStore(
  { providedIn: 'root' },

  withState(loadState()),

  withComputed((store) => {
    const permissionNames = computed(() => {
      return store.user()?.permissions.map(p => p.name) ?? [];
    });

    const permissions = computed(() => {
      return store.user()?.permissions ?? [];
    });

    // Tous les éléments de menu
    const menuItems = computed(() => {
      const userPermissions = permissions();
      const items: MenuItem[] = [];

      // Ajouter Overview pour tous les utilisateurs (même sans permission)
      const overviewItem: MenuItem = {
        id: 0,
        label: PERMISSION_LABELS['ACCESS_OVERVIEW'] || 'Overview',
        route: '/dashboard/overview',
        icon: PERMISSION_ICONS['ACCESS_OVERVIEW'] || 'activity',
        order: PERMISSION_ORDER['ACCESS_OVERVIEW'] || 5,
        permissionName: 'ACCESS_OVERVIEW'
      };
      items.push(overviewItem);

      // Ajouter les autres permissions
      for (const perm of userPermissions) {
        if (perm.action) {
          const menuItem = {
            id: perm.id,
            label: PERMISSION_LABELS[perm.name] || perm.description || perm.name,
            route: perm.action,
            icon: PERMISSION_ICONS[perm.name] || 'circle',
            order: PERMISSION_ORDER[perm.name] || 999,
            permissionName: perm.name
          };
          items.push(menuItem);
        }
      }
      return items.sort((a, b) => a.order - b.order);
    });

    // Pages principales pour la sidebar (avec Overview toujours inclus)
    const mainPages = computed(() => {
      // Prendre tous les items qui commencent par ACCESS_ (y compris Overview)
      return menuItems().filter(item =>
        item.permissionName.startsWith('ACCESS_') &&
        item.route.startsWith('/dashboard/')
      ).sort((a, b) => a.order - b.order);
    });

    // Tabs organisés par page parente avec routes relatives
    const tabsByParentPage = computed(() => {
      const tabs: Record<string, MenuItem[]> = {};
      for (const item of menuItems()) {
        const parentConfig = PARENT_PAGES[item.permissionName];
        if (parentConfig) {
          if (!tabs[parentConfig.parentRoute]) tabs[parentConfig.parentRoute] = [];

          // Créer un nouvel item avec la route relative
          const tabItem: MenuItem = {
            ...item,
            route: parentConfig.relativeRoute
          };

          tabs[parentConfig.parentRoute].push(tabItem);
        }
      }
      for (const parent in tabs) {
        tabs[parent].sort((a, b) => a.order - b.order);
      }
      return tabs;
    });

    // Créer un signal pour chaque page
    const administrationTabs = computed(() => {
      return tabsByParentPage()['/dashboard/administration'] || [];
    });

    const mlModelsTabs = computed(() => tabsByParentPage()['/dashboard/ml-models'] || []);
    const alertsTabs = computed(() => tabsByParentPage()['/dashboard/alerts'] || []);
    const importDataTabs = computed(() => tabsByParentPage()['/dashboard/import-data'] || []);
    const simulationTabs = computed(() => tabsByParentPage()['/dashboard/simulation'] || []);

    return {
      isLoggedIn: computed(() => store.isAuthenticated()),
      userName: computed(() => store.user()?.name ?? ''),
      userRole: computed(() => store.user()?.role ?? 'ANALYST'),
      userEmail: computed(() => store.user()?.email ?? ''),
      userFirstName: computed(() => store.user()?.firstName ?? ''),
      userLastName: computed(() => store.user()?.lastName ?? ''),
      userRoleId: computed(() => store.user()?.roleId),

      userRoleLabel: computed(() => {
        const role = store.user()?.role;
        switch (role) {
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

      // Signals spécifiques pour chaque page
      administrationTabs,
      mlModelsTabs,
      alertsTabs,
      importDataTabs,
      simulationTabs
    };
  }),

  withMethods((store, authService = inject(AuthService), router = inject(Router)) => ({

    login(credentials: LoginRequest): void {
      patchState(store, { loading: true, error: null });
      authService.login(credentials).subscribe({
        next: ({ response, user }) => {
          const newState: AuthState = { user, token: response.access_token, isAuthenticated: true, loading: false, error: null };
          patchState(store, newState);
          saveState(newState);
          router.navigate(['/dashboard/overview']);
        },
        error: (error) => {
          const errorState = { loading: false, error: error.message || 'Erreur de connexion' };
          patchState(store, errorState);
          setTimeout(() => {
            if (store.error() === errorState.error) patchState(store, { error: null });
          }, 5000);
        }
      });
    },

    logout(): void {
      authService.logout().subscribe({ next: () => this.clearSession(), error: () => this.clearSession() });
    },

    clearSession(): void {
      localStorage.removeItem(AUTH_STORAGE_KEY);
      patchState(store, initialAuthState);
      router.navigate(['/auth/login']);
    },

    clearError(): void {
      patchState(store, { error: null });
    },

    hasPermission(permissionName: string): boolean {
      // Overview est accessible à tous
      if (permissionName === 'ACCESS_OVERVIEW') return true;
      return store.permissionNames().includes(permissionName);
    },

    hasAnyPermission(permissionNames: string[]): boolean {
      // Si Overview est dans la liste, toujours true
      if (permissionNames.includes('ACCESS_OVERVIEW')) return true;
      const userPerms = store.permissionNames();
      return permissionNames.some(name => userPerms.includes(name));
    },

    hasAllPermissions(permissionNames: string[]): boolean {
      // Si Overview est dans la liste, on le retire car il est toujours vrai
      const perms = permissionNames.filter(p => p !== 'ACCESS_OVERVIEW');
      if (perms.length === 0) return true;
      const userPerms = store.permissionNames();
      return perms.every(name => userPerms.includes(name));
    },

    setLoading(loading: boolean): void {
      patchState(store, { loading });
    },

    // Méthode utilitaire pour obtenir les tabs d'une page
    getTabsForPage(pageRoute: string): MenuItem[] {
      return store.tabsByParentPage()[pageRoute] || [];
    }
  }))
);