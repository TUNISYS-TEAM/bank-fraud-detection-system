import { Component, EventEmitter, Output, inject, signal, Input, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../environments/environment';

export interface UserDetails {
  id: number;
  uuid: string;
  email: string;
  firstName: string;
  lastName: string;
  phoneNumber: string | null;
  address: string | null;
  dateOfBirth: string | null;
  roleId: number;
  roleName: string;
  managerId: number | null;
  managerName: string | null;
  isActive: boolean;
  lastLogin: string | null;
  createdAt: string;
  updatedAt: string;
}

@Component({
  selector: 'app-user-view-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './user-view-modal.component.html',
  styleUrls: ['./user-view-modal.component.css']
})
export class UserViewModalComponent implements OnInit {
  @Output() close = new EventEmitter<void>();
  
  @Input() set userId(id: number | null) {
    if (id) {
      this.userIdInternal.set(id);
      this.loadUserDetails(id);
    }
  }

  private http = inject(HttpClient);

  // Signals
  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  userIdInternal = signal<number | null>(null);
  userDetails = signal<UserDetails | null>(null);

  ngOnInit() {
    // No need to load roles anymore
  }

  loadUserDetails(id: number) {
    this.isLoading.set(true);
    
    this.http.get<UserDetails>(`${environment.apiUrl}/users/${id}`).subscribe({
      next: (user) => {
        // The API already returns roleName, so we just set the user details directly
        this.userDetails.set(user);
        this.isLoading.set(false);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set('Erreur lors du chargement des détails');
        console.error('Error loading user details:', err);
      }
    });
  }

  closeModal() {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  formatDate(date: string | null): string {
    if (!date) return 'Non renseigné';
    return new Date(date).toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric'
    });
  }

  formatDateTime(date: string | null): string {
    if (!date) return 'Non renseigné';
    return new Date(date).toLocaleString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }
}