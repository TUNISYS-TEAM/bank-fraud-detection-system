import { Component, Input, Output, EventEmitter, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../environments/environment';

export interface UpdateUserData {
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber?: string | null;
  address?: string | null;
  dateOfBirth?: string | null;
  roleId: number;
  isActive: boolean;
  managerId: number | null;
}

export interface User {
  id: number;
  uuid: string;
  email: string;
  firstName: string;
  lastName: string;
  phoneNumber?: string | null;
  address?: string | null;
  dateOfBirth?: string | null;
  roleId: number;
  roleName?: string;
  isActive: boolean;
  managerId?: number | null;
  managerName?: string | null;
  lastLogin: string | null;
}

export interface Role {
  id: number;
  name: string;
  description: string;
}

@Component({
  selector: 'app-user-update-modal',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './user-update-modal.component.html',
  styleUrls: ['./user-update-modal.component.css']
})
export class UserUpdateModalComponent implements OnInit {
  @Input() user: User | null = null;
  @Input() roles: Role[] = [];
  @Output() close = new EventEmitter<void>();
  @Output() updated = new EventEmitter<User>();

  private fb = inject(FormBuilder);
  private http = inject(HttpClient);

  // Signals
  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  availableManagers = signal<User[]>([]);
  filteredManagers = signal<User[]>([]);
  
  updateForm: FormGroup = this.fb.group({
    firstName: ['', [Validators.required, Validators.minLength(2)]],
    lastName: ['', [Validators.required, Validators.minLength(2)]],
    email: ['', [Validators.required, Validators.email]],
    phoneNumber: [''],
    address: [''],
    dateOfBirth: [''],
    roleId: [null, Validators.required],
    isActive: [true],
    managerId: [null]
  });

  ngOnInit(): void {
    this.loadAvailableManagers();
    
    // When role changes, filter managers based on role hierarchy
    this.updateForm.get('roleId')?.valueChanges.subscribe((roleId) => {
      this.filterManagersByRole(roleId);
    });
    
    if (this.user) {
      this.updateForm.patchValue({
        firstName: this.user.firstName,
        lastName: this.user.lastName,
        email: this.user.email,
        phoneNumber: this.user.phoneNumber || '',
        address: this.user.address || '',
        dateOfBirth: this.user.dateOfBirth || '',
        roleId: this.user.roleId,
        isActive: this.user.isActive,
        managerId: this.user.managerId || null
      });
      
      // Trigger filter after patch
      setTimeout(() => {
        this.filterManagersByRole(this.user!.roleId);
      });
    }
  }

  /**
   * Get role name by role ID
   */
  getRoleName(roleId: number | null): string | null {
    if (!roleId) return null;
    const role = this.roles.find(r => r.id === roleId);
    return role ? role.name : null;
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

    const selectedRole = this.roles.find(r => r.id === roleId);
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
      this.updateForm.get('managerId')?.disable();
      this.updateForm.get('managerId')?.setValue(null);
      this.filteredManagers.set([]);
    } else {
      this.updateForm.get('managerId')?.enable();
      // Filter managers that have allowed roles
      const filtered = this.availableManagers().filter(manager => 
        allowedRoles.includes(manager.roleName || '')
      );
      this.filteredManagers.set(filtered);
      
      // Also filter out the current user if they are in the list (can't be your own manager)
      if (this.user) {
        const withoutSelf = filtered.filter(m => m.id !== this.user!.id);
        this.filteredManagers.set(withoutSelf);
      }
    }
  }

  onSubmit(): void {
    if (this.updateForm.valid && this.user) {
      this.isLoading.set(true);
      this.errorMessage.set('');

      const updateData: UpdateUserData = {
        firstName: this.updateForm.value.firstName,
        lastName: this.updateForm.value.lastName,
        email: this.updateForm.value.email,
        phoneNumber: this.updateForm.value.phoneNumber || null,
        address: this.updateForm.value.address || null,
        dateOfBirth: this.updateForm.value.dateOfBirth || null,
        roleId: this.updateForm.value.roleId,
        isActive: this.updateForm.value.isActive,
        managerId: this.updateForm.value.managerId || null
      };

      this.http.put<User>(`${environment.apiUrl}/users/${this.user.id}`, updateData).subscribe({
        next: (updatedUser) => {
          this.isLoading.set(false);
          this.updated.emit(updatedUser);
          this.closeModal();
        },
        error: (err) => {
          this.isLoading.set(false);
          this.errorMessage.set(err.error?.message || 'Erreur lors de la mise à jour');
          console.error('Error updating user:', err);
        }
      });
    }
  }

  closeModal(): void {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  onClose(): void {
    this.closeModal();
  }
}