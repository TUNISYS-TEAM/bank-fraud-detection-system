import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { DatasetService, Dataset, DatasetStatus } from '../../../../core/services/dataset.service';
import { FeatherModule } from 'angular-feather';

@Component({
  selector: 'app-dataset-update-modal',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FeatherModule],
  templateUrl: './dataset-update-modal.component.html',
  styleUrls: ['./dataset-update-modal.component.css']
})
export class DatasetUpdateModalComponent {
  @Input({ required: true }) set dataset(value: Dataset) {
    this.selectedDataset.set(value);
    this.datasetForm.patchValue({
      datasetName: value.datasetName,
      visibility: value.visibility
    });
  }
  @Output() close = new EventEmitter<void>();
  @Output() updated = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private datasetService = inject(DatasetService);

  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  selectedDataset = signal<Dataset | null>(null);

  datasetForm: FormGroup = this.fb.group({
    datasetName: ['', [Validators.required, Validators.minLength(3)]],
    visibility: [false, Validators.required]
  });

  closeModal(): void {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  onSubmit(): void {
    if (this.datasetForm.invalid || !this.selectedDataset()) return;

    this.isLoading.set(true);
    this.errorMessage.set('');

    const uuid = this.selectedDataset()!.datasetUuid;
    const updateData = this.datasetForm.value;

    this.datasetService.update(uuid, updateData).subscribe({
      next: (response: any) => {
        this.isLoading.set(false);
        if (response?.error) {
          this.errorMessage.set(response.error);
        } else {
          this.updated.emit();
          this.closeModal();
        }
      },
      error: () => {
        this.isLoading.set(false);
        this.errorMessage.set('Erreur lors de la mise à jour du dataset');
      }
    });
  }

  // Helper to display status label
  getStatusLabel(status: DatasetStatus | null): string {
    if (!status) return 'Non traité';
    switch (status) {
      case DatasetStatus.PROCESSED: return 'Traité';
      case DatasetStatus.CLEANED: return 'Nettoyé';
      default: return status;
    }
  }

  // Helper to get status color class
  getStatusClass(status: DatasetStatus | null): string {
    if (!status) return 'bg-gray-100 text-gray-600';
    switch (status) {
      case DatasetStatus.PROCESSED: return 'bg-blue-50 text-blue-700';
      case DatasetStatus.CLEANED: return 'bg-purple-50 text-purple-700';
      default: return 'bg-gray-100 text-gray-600';
    }
  }
}