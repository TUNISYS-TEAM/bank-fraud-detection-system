import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../../environments/environment';
import { AuthStore } from '../../../../../../core/store/auth.store';
import { CreateRoleData } from '../../../../../../shared/modals/admin-sections/roles/role-create-modal/role-create-modal.component';
import { UpdateRoleData } from '../../../../../../shared/modals/admin-sections/roles/role-update-modal/role-update-modal.component';
import { DeleteRoleData } from '../../../../../../shared/modals/admin-sections/roles/role-delete-modal/role-delete-modal.component'; // IMPORT AJOUTÉ

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

  // Modals state
  showCreateModal = signal(false);
  showUpdateModal = signal(false);
  showDeleteModal = signal(false);
  selectedRole = signal<RoleWithPermissions | null>(null);
  roleToDelete = signal<DeleteRoleData | null>(null); // TYPE CORRIGÉ

  // Computed
  isLoading = computed(() => this.loading());

  ngOnInit(): void {
    this.loadRoles();
  }

  loadRoles(): void {
    this.loading.set(true);
    this.http.get<Role[]>(`${environment.apiUrl}/roles`).subscribe({
      next: (roles) => {
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

  getPermissionCount(permissions: Permission[]): number {
    return permissions.length;
  }

  refreshRole(roleId: number): void {
    this.loadPermissionsForRole(roleId);
  }

  // Create Modal methods
  openCreateModal(): void {
    this.showCreateModal.set(true);
  }

  closeCreateModal(): void {
    this.showCreateModal.set(false);
  }

  onRoleCreated(roleData: CreateRoleData): void {
    console.log('Role created:', roleData);
    this.loadRoles();
    this.closeCreateModal();
  }

  // Update Modal methods
  openUpdateModal(role: RoleWithPermissions): void {
    this.selectedRole.set(role);
    this.showUpdateModal.set(true);
  }

  closeUpdateModal(): void {
    this.showUpdateModal.set(false);
    setTimeout(() => this.selectedRole.set(null), 300);
  }

  onRoleUpdated(updatedData: UpdateRoleData): void {
    console.log('Role updated:', updatedData);
    this.loadRoles();
    this.closeUpdateModal();
  }

  editRole(role: RoleWithPermissions): void {
    this.openUpdateModal(role);
  }

  // Delete Modal methods
  openDeleteModal(role: RoleWithPermissions): void {
    // Créer un objet compatible avec DeleteRoleData
    const deleteRoleData: DeleteRoleData = {
      id: role.id,
      name: role.name,
      description: role.description,
      keycloakRoleName: role.keycloakRoleName,
      permissionsCount: role.permissions.length 
    };
    this.roleToDelete.set(deleteRoleData);
    this.showDeleteModal.set(true);
  }

  closeDeleteModal(): void {
    this.showDeleteModal.set(false);
    setTimeout(() => this.roleToDelete.set(null), 300);
  }

  onRoleDeleted(roleId: number): void {
    console.log('Role deleted:', roleId);
    this.loadRoles();
    this.closeDeleteModal();
  }

  // Utilitaires pour les icônes
  getPermissionIcon(permissionName: string): string {
    if (permissionName.includes('ACCESS')) return 'eye';
    if (permissionName.includes('MANAGE')) return 'settings';
    if (permissionName.includes('VIEW')) return 'eye';
    if (permissionName.includes('CREATE')) return 'plus-circle';
    if (permissionName.includes('EDIT')) return 'edit';
    if (permissionName.includes('DELETE')) return 'trash-2';
    return 'check-circle';
  }

  getPermissionColor(permissionName: string): string {
    if (permissionName.includes('ACCESS')) return 'text-green-600 bg-green-50';
    if (permissionName.includes('MANAGE')) return 'text-blue-600 bg-blue-50';
    if (permissionName.includes('VIEW')) return 'text-purple-600 bg-purple-50';
    if (permissionName.includes('CREATE')) return 'text-indigo-600 bg-indigo-50';
    if (permissionName.includes('EDIT')) return 'text-amber-600 bg-amber-50';
    if (permissionName.includes('DELETE')) return 'text-red-600 bg-red-50';
    return 'text-gray-600 bg-gray-50';
  }
}