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
}

interface User {
  id: number;
  uuid: string;
  email: string;
  firstName: string;
  lastName: string;
  phoneNumber?: string | null;
  address?: string | null;
  dateOfBirth?: string | null;
  roleId: number;
  isActive: boolean;
  lastLogin: string | null;
}

interface Role {
  id: number;
  name: string;
  description: string;
}

@Component({
  selector: 'app-user-update-modal',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './user-update-modal.component.html',
  styleUrl: './user-update-modal.component.css'
})
export class UserUpdateModalComponent implements OnInit {
  @Input() user: User | null = null;
  @Input() roles: Role[] = [];
  @Output() close = new EventEmitter<void>();
  @Output() updated = new EventEmitter<User>();

  private fb = inject(FormBuilder);
  private http = inject(HttpClient);

  // Signals pour l'état local
  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  
  updateForm: FormGroup = this.fb.group({
    firstName: ['', [Validators.required, Validators.minLength(2)]],
    lastName: ['', [Validators.required, Validators.minLength(2)]],
    email: ['', [Validators.required, Validators.email]],
    phoneNumber: [''],
    address: [''],
    dateOfBirth: [''],
    roleId: [null, Validators.required],
    isActive: [true]
  });

  ngOnInit(): void {
    if (this.user) {
      this.updateForm.patchValue({
        firstName: this.user.firstName,
        lastName: this.user.lastName,
        email: this.user.email,
        phoneNumber: this.user.phoneNumber || '',
        address: this.user.address || '',
        dateOfBirth: this.user.dateOfBirth || '',
        roleId: this.user.roleId,
        isActive: this.user.isActive
      });
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
        isActive: this.updateForm.value.isActive
      };

      // Appel API PUT pour mettre à jour l'utilisateur
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