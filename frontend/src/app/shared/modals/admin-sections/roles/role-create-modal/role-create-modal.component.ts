import { Component, EventEmitter, Output, inject, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../environments/environment';
import { FeatherModule } from 'angular-feather';

export interface CreateRoleData {
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

@Component({
  selector: 'app-role-create-modal',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FeatherModule],
  templateUrl: './role-create-modal.component.html',
  styleUrls: ['./role-create-modal.component.css']
})
export class RoleCreateModalComponent implements OnInit {
  @Output() close = new EventEmitter<void>();
  @Output() roleCreated = new EventEmitter<CreateRoleData>();

  private fb = inject(FormBuilder);
  private http = inject(HttpClient);

  // Signals
  isVisible = signal(true);
  isLoading = signal(false);
  permissions = signal<Permission[]>([]);
  selectedPermissions = signal<Set<number>>(new Set());
  errorMessage = signal('');

  roleForm: FormGroup = this.fb.group({
    name: ['', [Validators.required, Validators.minLength(2)]],
    description: ['', Validators.required]
  });

  ngOnInit() {
    this.loadPermissions();
  }

  loadPermissions() {
    this.http.get<Permission[]>(`${environment.apiUrl}/permissions`).subscribe({
      next: (perms) => {
        this.permissions.set(perms);
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
    if (this.roleForm.invalid) {
      Object.keys(this.roleForm.controls).forEach(key => {
        this.roleForm.get(key)?.markAsTouched();
      });
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    const roleData: CreateRoleData = {
      name: this.roleForm.value.name,
      description: this.roleForm.value.description,
      permissionIds: Array.from(this.selectedPermissions())
    };

    this.http.post(`${environment.apiUrl}/roles`, roleData).subscribe({
      next: (response) => {
        this.isLoading.set(false);
        this.roleCreated.emit(roleData);
        this.closeModal();
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la création du rôle');
        console.error('Error creating role:', err);
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