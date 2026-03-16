import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../environments/environment';
import { FeatherModule } from 'angular-feather';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-history-delete-modal',
  templateUrl: './history-delete-modal.component.html',
  styleUrls: ['./history-delete-modal.component.css'],
  standalone: true,
  imports: [CommonModule, FeatherModule]  // Add FeatherModule here
})
export class DeleteHistoryModalComponent {
  @Input({ required: true }) model: any;
  @Output() close = new EventEmitter<void>();
  @Output() deleted = new EventEmitter<void>();

  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');

  constructor(private http: HttpClient) {}

  closeModal(): void {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  confirmDelete(): void {
    this.isLoading.set(true);
    this.errorMessage.set('');

    this.http.delete(`${environment.apiUrl}/v1/fraud-detection/models/training/${this.model.modelUuid}`).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.deleted.emit();
        this.closeModal();
      },
      error: (err: any) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la suppression du modèle');
        console.error('Error deleting model:', err);
      }
    });
  }
}