import { signalStore, withState, withComputed, withMethods, patchState } from '@ngrx/signals';
import { computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { LoginRequest } from '../../core/services/auth.service';

export interface AuthUser {
  id: string;
  email: string;
  name: string;
  role: string;
}

export interface AuthState {
  user: AuthUser | null;
  token: string | null;
  isAuthenticated: boolean;
  loading: boolean;
  error: string | null;
}

const initialAuthState: AuthState = {
  user: null,
  token: null,
  isAuthenticated: false,
  loading: false,
  error: null
};

export const AuthStore = signalStore(
  { providedIn: 'root' },
  
  withState(initialAuthState),
  
  withComputed((store) => ({
    isLoggedIn: computed(() => store.isAuthenticated()),
    userName: computed(() => store.user()?.name ?? ''),
    userRole: computed(() => store.user()?.role ?? ''),
    isAdmin: computed(() => store.user()?.role === 'ADMIN'),
    isLoading: computed(() => store.loading()),
    errorMessage: computed(() => store.error())
  })),
  
  withMethods((store, authService = inject(AuthService), router = inject(Router)) => ({
    
    login(credentials: LoginRequest): void {
      patchState(store, { loading: true, error: null });
      
      authService.login(credentials).subscribe({
        next: (response) => {
          // Extraire les infos du token (à adapter selon ton token)
          const tokenData = this.parseJwt(response.access_token);
          
          const user: AuthUser = {
            id: response.session_state || '',
            email: credentials.email,
            name: tokenData?.name || credentials.email,
            role: tokenData?.realm_access?.roles?.includes('ADMIN') ? 'ADMIN' : 'USER'
          };
          
          patchState(store, {
            user,
            token: response.access_token,
            isAuthenticated: true,
            loading: false
          });
          
          router.navigate(['/dashboard']);
        },
        error: (error) => {
          patchState(store, {
            loading: false,
            error: error.message
          });
        }
      });
    },
    
    logout(): void {
      authService.logout();
      patchState(store, initialAuthState);
      router.navigate(['/auth/login']);
    },
    
    clearError(): void {
      patchState(store, { error: null });
    },

    parseJwt(token: string): any {
      try {
        return JSON.parse(atob(token.split('.')[1]));
      } catch {
        return null;
      }
    }
  }))
);