import { Component, EventEmitter, Output, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../environments/environment';

export interface CreateUserData {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  isActive: boolean;
  roleId: number;
  managerId: number | null;
}

export interface User {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
  roleId: number;
  roleName: string;
}

export interface Role {
  id: number;
  name: string;
  description: string;
}

@Component({
  selector: 'app-user-create-modal',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './user-create-modal.component.html',
  styleUrls: ['./user-create-modal.component.css']
})
export class UserCreateModalComponent implements OnInit {
  @Output() close = new EventEmitter<void>();
  @Output() userCreated = new EventEmitter<CreateUserData>();

  private fb = inject(FormBuilder);
  private http = inject(HttpClient);

  // Signals
  isVisible = signal(true);
  isLoading = signal(false);
  roles = signal<Role[]>([]);
  availableManagers = signal<User[]>([]);
  filteredManagers = signal<User[]>([]);
  errorMessage = signal('');

  userForm: FormGroup = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(6)]],
    firstName: ['', Validators.required],
    lastName: ['', Validators.required],
    isActive: [true],
    roleId: ['', Validators.required],
    managerId: [null]
  });

  ngOnInit() {
    this.loadRoles();
    this.loadAvailableManagers();

    // When role changes, filter managers based on role hierarchy
    this.userForm.get('roleId')?.valueChanges.subscribe((roleId) => {
      this.filterManagersByRole(roleId);
    });
  }

  /**
   * Get role name by role ID
   */
  getRoleName(roleId: number | null): string | null {
    if (!roleId) return null;
    const role = this.roles().find(r => r.id === roleId);
    return role ? role.name : null;
  }

  loadRoles() {
    this.http.get<Role[]>(`${environment.apiUrl}/roles`).subscribe({
      next: (roles) => {
        this.roles.set(roles);
      },
      error: (err) => {
        console.error('Error loading roles:', err);
      }
    });
  }

  loadAvailableManagers() {
    this.http.get<User[]>(`${environment.apiUrl}/users/available-managers`).subscribe({
      next: (managers) => {
        this.availableManagers.set(managers);
        this.filteredManagers.set(managers);
      },
      error: (err) => {
        console.error('Error loading managers:', err);
      }
    });
  }

  filterManagersByRole(roleId: number) {
    if (!roleId) {
      this.filteredManagers.set(this.availableManagers());
      return;
    }

    const selectedRole = this.roles().find(r => r.id === roleId);
    if (!selectedRole) {
      this.filteredManagers.set(this.availableManagers());
      return;
    }

    // Define allowed manager roles based on selected user role
    const allowedManagerRoles: Record<string, string[]> = {
      'ANALYST': ['MANAGER', 'ADMIN'],
      'MANAGER': ['ADMIN'],
      'ADMIN': []
    };

    const allowedRoles = allowedManagerRoles[selectedRole.name] || [];

    if (allowedRoles.length === 0) {
      // User cannot have a manager, disable the field
      this.userForm.get('managerId')?.disable();
      this.userForm.get('managerId')?.setValue(null);
      this.filteredManagers.set([]);
    } else {
      this.userForm.get('managerId')?.enable();
      // Filter managers that have allowed roles
      const filtered = this.availableManagers().filter(manager =>
        allowedRoles.includes(manager.roleName || '')
      );
      this.filteredManagers.set(filtered);
    }
  }

  closeModal() {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  onSubmit() {
    if (this.userForm.invalid) {
      Object.keys(this.userForm.controls).forEach(key => {
        this.userForm.get(key)?.markAsTouched();
      });
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    const userData: CreateUserData = this.userForm.value;

    this.http.post(`${environment.apiUrl}/users`, userData).subscribe({
      next: (response) => {
        this.isLoading.set(false);
        this.userCreated.emit(userData);
        this.closeModal();
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la création de l\'utilisateur');
        console.error('Error creating user:', err);

        // Scroll automatically to the error message so the user can see it
        setTimeout(() => {
          const errorContainer = document.getElementById('error-message-container-create');
          if (errorContainer) {
            errorContainer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
          }
        }, 100);
      }
    });
  }
}