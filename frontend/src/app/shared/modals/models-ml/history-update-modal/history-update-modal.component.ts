import { Component, EventEmitter, Input, OnInit, Output, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../environments/environment';
import { FormsModule } from '@angular/forms';
import { FeatherModule } from 'angular-feather';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-history-update-modal',
  templateUrl: './history-update-modal.component.html',
  styleUrls: ['./history-update-modal.component.css'],
  standalone: true,
  imports: [CommonModule, FormsModule, FeatherModule]  // Add FeatherModule here
})
export class UpdateHistoryModalComponent implements OnInit {
  @Input({ required: true }) model: any;
  @Output() close = new EventEmitter<void>();
  @Output() updated = new EventEmitter<void>();

  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  newName: string = '';

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.newName = this.model.modelName;
  }

  closeModal(): void {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  confirmUpdate(): void {
    if (!this.newName.trim()) {
      this.errorMessage.set('Le nom ne peut pas être vide');
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    this.http.patch(`${environment.apiUrl}/v1/fraud-detection/models/training/${this.model.modelUuid}/name`, { name: this.newName }).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.updated.emit();
        this.closeModal();
      },
      error: (err: any) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la mise à jour du nom');
        console.error('Error updating name:', err);
      }
    });
  }
}