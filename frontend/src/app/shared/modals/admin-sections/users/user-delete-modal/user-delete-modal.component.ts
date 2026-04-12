import { Component, EventEmitter, Output, inject, signal, Input, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../environments/environment';
import { FraudAlertService } from '../../../../../core/services/fraud-alert.service';

export interface DeleteUserData {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
}

@Component({
  selector: 'app-user-delete-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './user-delete-modal.component.html',
  styleUrls: ['./user-delete-modal.component.css']
})
export class UserDeleteModalComponent implements OnInit {
  @Output() close = new EventEmitter<void>();
  @Output() userDeleted = new EventEmitter<number>();
  
  // Input pour recevoir les données de l'utilisateur
  @Input() set userData(user: DeleteUserData | null) {
    if (user) {
      this.userDataInternal.set(user);
    }
  }

  private http = inject(HttpClient);
  private fraudAlertService = inject(FraudAlertService);

  // Signals
  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  userDataInternal = signal<DeleteUserData | null>(null);
  alertCount = signal<number>(0);
  managerName = signal<string>('');
  isCheckingAlerts = signal<boolean>(false);

  ngOnInit() {
    const user = this.userDataInternal();
    if (user) {
        this.checkAlertsAndManager(user.id);
    }
  }

  private checkAlertsAndManager(userId: number) {
    this.isCheckingAlerts.set(true);
    
    // First get the user details to know the manager
    this.http.get<any>(`${environment.apiUrl}/users/${userId}`).subscribe({
        next: (user) => {
            this.managerName.set(user.managerName || 'N/A');
            
            // Then check the alert count if they are an analyst
            if (user.roleName === 'ANALYST') {
                this.fraudAlertService.countAlertsByAnalyst(userId).subscribe({
                    next: (count: number) => {
                        this.alertCount.set(count);
                        this.isCheckingAlerts.set(false);
                    },
                    error: () => this.isCheckingAlerts.set(false)
                });
            } else {
                this.isCheckingAlerts.set(false);
            }
        },
        error: () => this.isCheckingAlerts.set(false)
    });
  }

  closeModal() {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  confirmDelete() {
    const user = this.userDataInternal();
    if (!user) return;

    this.isLoading.set(true);
    this.errorMessage.set('');

    this.http.delete(`${environment.apiUrl}/users/${user.id}`).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.userDeleted.emit(user.id);
        this.closeModal();
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la suppression de l\'utilisateur');
        console.error('Error deleting user:', err);
      }
    });
  }
}