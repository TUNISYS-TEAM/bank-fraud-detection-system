import { signalStore, withState, withComputed, withMethods, patchState } from '@ngrx/signals';
import { computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { AuthUser, LoginRequest, MenuItem } from '../models/auth.model';

const PERMISSION_ICONS: Record<string, string> = {
  // Pages principales
  'ACCESS_ADMINISTRATION': 'cogs',
  'ACCESS_IMPORT_DATA': 'upload',
  'ACCESS_ML_MODELS': 'brain',
  'ACCESS_ALERTS': 'bell',
  'ACCESS_PERFORMANCE': 'chart-line',
  'ACCESS_SIMULATION': 'flask',
  
  // Actions de gestion
  'MANAGE_USERS': 'users-cog',
  'MANAGE_ROLES': 'key',
  'MANAGE_SESSIONS': 'history',
  'MANAGE_ML_MODELS': 'robot',
  'MANAGE_ALERTS': 'exclamation-triangle',
  'IMPORT_DATA': 'file-import',
  'RUN_SIMULATION': 'play',
  'MANAGE_LOGS': 'scroll',
  'MANAGE_SETTINGS': 'sliders',
  'MANAGE_DATASETS': 'database'
};

const PERMISSION_LABELS: Record<string, string> = {
  // Pages principales
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

// Configuration des pages parentes pour les tabs
const PARENT_PAGES: Record<string, string> = {
  // Administration
  'MANAGE_USERS': '/dashboard/administration',
  'MANAGE_ROLES': '/dashboard/administration',
  'MANAGE_SESSIONS': '/dashboard/administration',
  'MANAGE_LOGS': '/dashboard/administration',
  'MANAGE_SETTINGS': '/dashboard/administration',
  'MANAGE_DATASETS': '/dashboard/administration',
  
  // Modèles ML
  'MANAGE_ML_MODELS': '/dashboard/ml-models',
  
  // Alertes
  'MANAGE_ALERTS': '/dashboard/alerts',
  
  // Import
  'IMPORT_DATA': '/dashboard/import-data',
  
  // Simulation
  'RUN_SIMULATION': '/dashboard/simulation'
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
    console.error('Error loading auth state:', error);
    return initialAuthState;
  }
}

function saveState(state: AuthState): void {
  try {
    localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(state));
  } catch (error) {
    console.error('Error saving auth state:', error);
  }
}

export const AuthStore = signalStore(
  { providedIn: 'root' },

  withState(loadState()),

  withComputed((store) => {
    console.log('👤 Store user:', store.user());
    console.log('📦 Store permissions:', store.user()?.permissions);

    const permissionNames = computed(() => {
      const names = store.user()?.permissions.map(p => p.name) ?? [];
      console.log('📝 Permission names:', names);
      return names;
    });

    const permissions = computed(() => {
      const perms = store.user()?.permissions ?? [];
      console.log('🔑 Permissions objects:', perms);
      return perms;
    });

    // Tous les éléments de menu
    const menuItems = computed(() => {
      const userPermissions = permissions();
      console.log('🏗️ Building menu items from:', userPermissions);
      
      const items: MenuItem[] = [];

      for (const perm of userPermissions) {
        console.log('  → Processing permission:', perm.name, perm.action);
        if (perm.action) {
          const menuItem = {
            id: perm.id,
            label: PERMISSION_LABELS[perm.name] || perm.description || perm.name,
            route: perm.action,
            icon: PERMISSION_ICONS[perm.name] || 'circle',
            order: PERMISSION_ORDER[perm.name] || 999,
            permissionName: perm.name
          };
          console.log('    ✅ Added menu item:', menuItem);
          items.push(menuItem);
        } else {
          console.log('    ❌ No action for permission:', perm.name);
        }
      }
      console.log('📊 Final menu items:', items);
      return items.sort((a, b) => a.order - b.order);
    });

    // Pages principales pour la sidebar (uniquement les ACCESS_* permissions)
    const mainPages = computed(() => {
      const pages = menuItems().filter(item => 
        item.permissionName.startsWith('ACCESS_') &&
        item.route.startsWith('/dashboard/')
      ).sort((a, b) => a.order - b.order);
      console.log('📌 Main pages (sidebar):', pages);
      return pages;
    });

    // Tabs organisés par page parente
    const tabsByParentPage = computed(() => {
      const tabs: Record<string, MenuItem[]> = {};
      for (const item of menuItems()) {
        const parentRoute = PARENT_PAGES[item.permissionName];
        if (parentRoute) {
          if (!tabs[parentRoute]) tabs[parentRoute] = [];
          tabs[parentRoute].push(item);
        }
      }
      for (const parent in tabs) {
        tabs[parent].sort((a, b) => a.order - b.order);
      }
      console.log('📑 Tabs by parent page:', tabs);
      return tabs;
    });

    // Créer un signal pour chaque page
    const administrationTabs = computed(() => {
      const tabs = tabsByParentPage()['/dashboard/administration'] || [];
      console.log('🏢 Administration tabs:', tabs);
      return tabs;
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
          console.log('✅ Login successful, user:', user);
          const newState: AuthState = { user, token: response.access_token, isAuthenticated: true, loading: false, error: null };
          patchState(store, newState);
          saveState(newState);
          router.navigate(['/dashboard/overview']);
        },
        error: (error) => {
          console.error('❌ Login error:', error);
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
      return store.permissionNames().includes(permissionName);
    },

    hasAnyPermission(permissionNames: string[]): boolean {
      const userPerms = store.permissionNames();
      return permissionNames.some(name => userPerms.includes(name));
    },

    hasAllPermissions(permissionNames: string[]): boolean {
      const userPerms = store.permissionNames();
      return permissionNames.every(name => userPerms.includes(name));
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