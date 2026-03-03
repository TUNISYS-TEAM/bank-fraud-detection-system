import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DatasetService, Dataset } from '../../../../../core/services/dataset.service';
import { FeatherModule } from 'angular-feather';

@Component({
  selector: 'app-dataset-delete-modal',
  standalone: true,
  imports: [CommonModule, FeatherModule],
  templateUrl: './dataset-delete-modal.component.html',
  styleUrls: ['./dataset-delete-modal.component.css']
})
export class DatasetDeleteModalComponent {
  @Input({ required: true }) dataset!: Dataset;
  @Output() close = new EventEmitter<void>();
  @Output() deleted = new EventEmitter<void>();

  private datasetService = inject(DatasetService);

  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');

  closeModal(): void {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  confirmDelete(): void {
    this.isLoading.set(true);
    this.errorMessage.set('');

    this.datasetService.delete(this.dataset.datasetUuid).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.deleted.emit();
        this.closeModal();
      },
      error: (err: any) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la suppression du dataset');
        console.error('Error deleting dataset:', err);
      }
    });
  }
}