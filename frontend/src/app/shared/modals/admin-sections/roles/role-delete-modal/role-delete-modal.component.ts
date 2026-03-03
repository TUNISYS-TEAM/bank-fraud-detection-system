import { Component, EventEmitter, Output, inject, signal, Input, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../environments/environment';

export interface DeleteRoleData {
  id: number;
  name: string;
  description: string;
  keycloakRoleName: string;
  permissionsCount: number;
}

@Component({
  selector: 'app-role-delete-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './role-delete-modal.component.html',
  styleUrls: ['./role-delete-modal.component.css']
})
export class RoleDeleteModalComponent implements OnInit {
  @Input() role: DeleteRoleData | null = null;
  @Output() close = new EventEmitter<void>();
  @Output() roleDeleted = new EventEmitter<number>();

  private http = inject(HttpClient);

  // Signals
  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  dependencies = signal<any[]>([]);
  loadingDependencies = signal(false);

  ngOnInit() {
    if (this.role) {
      this.checkDependencies();
    }
  }

  checkDependencies() {
    if (!this.role) return;

    this.loadingDependencies.set(true);
    this.http.get<any>(`${environment.apiUrl}/users/by-role/${this.role.id}?page=0&size=5`).subscribe({
      next: (response) => {
        const users = response?.content ?? [];
        this.dependencies.set(users);
        this.loadingDependencies.set(false);
      },
      error: (err) => {
        console.error('Error checking dependencies:', err);
        this.loadingDependencies.set(false);
      }
    });
  }

  closeModal() {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  confirmDelete() {
    // Vérification de sécurité
    if (!this.role) {
      console.error('Aucun rôle sélectionné pour la suppression');
      this.errorMessage.set('Erreur : aucun rôle sélectionné');
      return;
    }

    // Vérification des dépendances
    if (this.hasDependencies()) {
      this.errorMessage.set('Impossible de supprimer un rôle utilisé par des utilisateurs');
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    const roleId = this.role.id;

    this.http.delete(`${environment.apiUrl}/roles/${roleId}`).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.roleDeleted.emit(roleId);
        this.closeModal();
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la suppression du rôle');
        console.error('Erreur lors de la suppression du rôle:', err);
      }
    });
  }

  hasDependencies(): boolean {
    return this.dependencies().length > 0;
  }
}