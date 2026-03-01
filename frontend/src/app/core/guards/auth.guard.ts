import { inject } from '@angular/core';
import { CanActivateFn, Router, ActivatedRouteSnapshot } from '@angular/router';
import { AuthStore } from '../store/auth.store';

export const authGuard: CanActivateFn = (route, state) => {
  const authStore = inject(AuthStore);
  const router = inject(Router);

  if (!authStore.isLoggedIn()) {
    router.navigate(['/auth/login'], {
      queryParams: { returnUrl: state.url }
    });
    return false;
  }

  const requiredRoles = route.data['roles'] as string[] | undefined;
  if (requiredRoles?.length) {
    const userRole = authStore.userRole();
    if (!requiredRoles.includes(userRole)) {
      router.navigate(['/dashboard/overview']);
      return false;
    }
  }

  const requiredPermissions = route.data['permissions'] as string[] | undefined;
  if (requiredPermissions?.length) {
    const hasPermission = authStore.hasAnyPermission(requiredPermissions);
    if (!hasPermission) {
      router.navigate(['/dashboard/overview']);
      return false;
    }
  }

  return true;
};

export const adminGuard: CanActivateFn = (route, state) => {
  const authStore = inject(AuthStore);
  const router = inject(Router);

  if (!authStore.isLoggedIn()) {
    router.navigate(['/auth/login'], {
      queryParams: { returnUrl: state.url }
    });
    return false;
  }

  if (!authStore.isAdmin()) {
    router.navigate(['/dashboard/overview']);
    return false;
  }

  return true;
};

export const permissionGuard: CanActivateFn = (route, state) => {
  const authStore = inject(AuthStore);
  const router = inject(Router);

  if (!authStore.isLoggedIn()) {
    router.navigate(['/auth/login'], {
      queryParams: { returnUrl: state.url }
    });
    return false;
  }

  const requiredPermission = route.data['permission'] as string | undefined;
  const failRedirect = route.data['permissionFailRedirect'] || '/dashboard/overview';

  if (requiredPermission && !authStore.hasPermission(requiredPermission)) {
    router.navigate([failRedirect]);
    return false;
  }

  return true;
};