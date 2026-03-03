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

  // Conversion en signals
  isVisible = signal(true);
  isLoading = signal(false);
  roles = signal<any[]>([]);
  errorMessage = signal('');

  userForm: FormGroup = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(6)]],
    firstName: ['', Validators.required],
    lastName: ['', Validators.required],
    isActive: [true], 
    roleId: ['', Validators.required]
  });

  ngOnInit() {
    this.loadRoles();
  }

  loadRoles() {
    this.http.get<any[]>(`${environment.apiUrl}/roles`).subscribe({
      next: (roles) => {
        this.roles.set(roles); 
      },
      error: (err) => {
        console.error('Error loading roles:', err);
      }
    });
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
      }
    });
  }
}