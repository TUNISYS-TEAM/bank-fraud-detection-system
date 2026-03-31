import { Component, EventEmitter, Output, inject, signal, Input, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../environments/environment';
import { AuthStore } from '../../../../../core/store/auth.store';
import { FeatherModule } from 'angular-feather';

export interface UpdateRoleData {
  id: number;
  name: string;
  description: string;
  permissionIds: number[];
}

interface Permission {
  id: number;
  name: string;
  description: string;
  action: string;
}

interface Role {
  id: number;
  name: string;
  description: string;
  keycloakRoleName: string;
}

@Component({
  selector: 'app-role-update-modal',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FeatherModule],
  templateUrl: './role-update-modal.component.html',
  styleUrls: ['./role-update-modal.component.css']
})
export class RoleUpdateModalComponent implements OnInit {
  @Input() role: Role | null = null;
  @Input() currentPermissions: Permission[] = [];
  @Output() close = new EventEmitter<void>();
  @Output() roleUpdated = new EventEmitter<UpdateRoleData>();

  private fb = inject(FormBuilder);
  private http = inject(HttpClient);
  private authStore = inject(AuthStore);

  // Signals
  isVisible = signal(true);
  isLoading = signal(false);
  allPermissions = signal<Permission[]>([]);
  selectedPermissions = signal<Set<number>>(new Set());
  errorMessage = signal('');

  roleForm: FormGroup = this.fb.group({
    name: ['', [Validators.required, Validators.minLength(2)]],
    description: ['', Validators.required]
  });

  ngOnInit() {
    this.loadAllPermissions();
  }

  loadAllPermissions() {
    this.http.get<Permission[]>(`${environment.apiUrl}/permissions`).subscribe({
      next: (perms) => {
        this.allPermissions.set(perms);
        // Pré-sélectionner les permissions actuelles
        if (this.currentPermissions.length > 0) {
          const selected = new Set(this.currentPermissions.map(p => p.id));
          this.selectedPermissions.set(selected);
        }
        // Remplir le formulaire avec les données du rôle
        if (this.role) {
          this.roleForm.patchValue({
            name: this.role.name,
            description: this.role.description
          });
        }
      },
      error: (err) => {
        console.error('Error loading permissions:', err);
      }
    });
  }

  togglePermission(permissionId: number) {
    const current = this.selectedPermissions();
    if (current.has(permissionId)) {
      current.delete(permissionId);
    } else {
      current.add(permissionId);
    }
    this.selectedPermissions.set(new Set(current));
  }

  isPermissionSelected(permissionId: number): boolean {
    return this.selectedPermissions().has(permissionId);
  }

  closeModal() {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  onSubmit() {
    if (this.roleForm.invalid || !this.role) {
      Object.keys(this.roleForm.controls).forEach(key => {
        this.roleForm.get(key)?.markAsTouched();
      });
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    const roleData: UpdateRoleData = {
      id: this.role.id,
      name: this.roleForm.value.name,
      description: this.roleForm.value.description,
      permissionIds: Array.from(this.selectedPermissions())
    };

    this.http.put(`${environment.apiUrl}/roles/${this.role.id}`, roleData).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.authStore.refreshUserProfile();
        this.roleUpdated.emit(roleData);
        this.closeModal();
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la mise à jour du rôle');
        console.error('Error updating role:', err);
      }
    });
  }

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
    if (permissionName.includes('ACCESS')) return 'text-green-500 bg-green-50 border-green-100';
    if (permissionName.includes('MANAGE')) return 'text-blue-500 bg-blue-50 border-blue-100';
    if (permissionName.includes('VIEW')) return 'text-purple-500 bg-purple-50 border-purple-100';
    if (permissionName.includes('CREATE')) return 'text-indigo-500 bg-indigo-50 border-indigo-100';
    if (permissionName.includes('EDIT')) return 'text-amber-500 bg-amber-50 border-amber-100';
    if (permissionName.includes('DELETE')) return 'text-red-500 bg-red-50 border-red-100';
    return 'text-gray-500 bg-gray-50 border-gray-100';
  }
}