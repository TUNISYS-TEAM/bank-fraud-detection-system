import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../../environments/environment';
import { AuthStore } from '../../../../../../core/store/auth.store';

interface Role {
  id: number;
  name: string;
  description: string;
  keycloakRoleName: string;
}

interface Permission {
  id: number;
  name: string;
  description: string;
  action: string;
}

interface RoleWithPermissions extends Role {
  permissions: Permission[];
  expanded?: boolean;
}

@Component({
  selector: 'app-roles',
  templateUrl: './roles.component.html',
  styleUrl: './roles.component.css'
})
export class RolesComponent implements OnInit {
  private http = inject(HttpClient);
  authStore = inject(AuthStore);

  // State
  roles = signal<RoleWithPermissions[]>([]);
  loading = signal(false);
  expandedRole = signal<number | null>(null);

  // Computed
  isLoading = computed(() => this.loading());

  ngOnInit(): void {
    this.loadRoles();
  }

  loadRoles(): void {
    this.loading.set(true);
    this.http.get<Role[]>(`${environment.apiUrl}/roles`).subscribe({
      next: (roles) => {
        // Initialiser les rôles sans permissions
        const rolesWithPermissions: RoleWithPermissions[] = roles.map(role => ({
          ...role,
          permissions: [],
          expanded: false
        }));
        this.roles.set(rolesWithPermissions);
        this.loading.set(false);

        // Charger les permissions pour chaque rôle
        this.loadPermissionsForAllRoles(rolesWithPermissions);
      },
      error: (error) => {
        console.error('Error loading roles:', error);
        this.loading.set(false);
      }
    });
  }

  loadPermissionsForAllRoles(roles: RoleWithPermissions[]): void {
    roles.forEach(role => {
      this.loadPermissionsForRole(role.id);
    });
  }

  loadPermissionsForRole(roleId: number): void {
    this.http.get<Permission[]>(`${environment.apiUrl}/roles/${roleId}/permissions`).subscribe({
      next: (permissions) => {
        this.roles.update(roles =>
          roles.map(r =>
            r.id === roleId
              ? { ...r, permissions: permissions }
              : r
          )
        );
      },
      error: (error) => {
        console.error(`Error loading permissions for role ${roleId}:`, error);
      }
    });
  }

  toggleRole(roleId: number): void {
    this.expandedRole.update(current => current === roleId ? null : roleId);
  }

  getPermissionNames(permissions: Permission[]): string {
    return permissions.map(p => p.name).join(', ');
  }

  getPermissionCount(permissions: Permission[]): number {
    return permissions.length;
  }

  refreshRole(roleId: number): void {
    this.loadPermissionsForRole(roleId);
  }

  editRole(role: RoleWithPermissions): void {
    console.log('Edit role:', role);

  }
}