import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { DatasetService, Dataset, DatasetStatus } from '../../../../../core/services/dataset.service';
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
      status: value.status
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
    status: [DatasetStatus.ACTIVE, Validators.required]
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
      next: () => {
        this.isLoading.set(false);
        this.updated.emit();
        this.closeModal();
      },
      error: (err: any) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la mise à jour du dataset');
        console.error('Error updating dataset:', err);
      }
    });
  }
}