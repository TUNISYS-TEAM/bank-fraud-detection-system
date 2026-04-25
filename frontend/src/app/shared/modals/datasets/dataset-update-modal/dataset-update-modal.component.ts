import { Component, EventEmitter, Input, OnInit, Output, inject, signal } from '@angular/core';
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
export class DatasetUpdateModalComponent implements OnInit {
  @Input({ required: true }) set dataset(value: Dataset) {
    this.selectedDataset.set(value);
    this.datasetForm.patchValue({
      datasetName: value.datasetName,
      visibility: value.visibility,
      assignedManagerId: value.assignedManagerId || null
    });
    this.updateManagerValidation(value.visibility);
    this.checkEnrichmentStatus(value.datasetUuid, value.type);
  }
  @Output() close = new EventEmitter<void>();
  @Output() updated = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private datasetService = inject(DatasetService);

  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  selectedDataset = signal<Dataset | null>(null);
  managers = signal<any[]>([]);
  isEnriched = signal<boolean>(true); // Default to true to not block other types

  datasetForm: FormGroup = this.fb.group({
    datasetName: ['', [Validators.required, Validators.minLength(3)]],
    visibility: [false, Validators.required],
    assignedManagerId: [null]
  });

  ngOnInit(): void {
    this.loadManagers();
    
    // Watch visibility change to trigger manager requirement
    this.datasetForm.get('visibility')?.valueChanges.subscribe(visible => {
      this.updateManagerValidation(visible);
    });
  }

  loadManagers(): void {
    this.datasetService.getManagers().subscribe({
      next: (data) => {
        // Filtrer pour exclure les administrateurs et ne garder que les vrais managers
        const managersOnly = data.filter((m: any) => m.roleName !== 'ADMIN');
        this.managers.set(managersOnly);
      },
      error: (err) => console.error('Error loading managers', err)
    });
  }

  updateManagerValidation(visible: boolean): void {
    const managerControl = this.datasetForm.get('assignedManagerId');
    if (visible && this.selectedDataset()?.type === 'TRANSACTIONS_VAL') {
      managerControl?.setValidators([Validators.required]);
    } else {
      managerControl?.clearValidators();
    }
    managerControl?.updateValueAndValidity();
  }
  
  checkEnrichmentStatus(uuid: string, type: string): void {
    if (type === 'TRANSACTIONS_TRAIN') {
      this.datasetService.checkIfEnriched(uuid).subscribe({
        next: (enriched) => this.isEnriched.set(enriched),
        error: () => this.isEnriched.set(false)
      });
    } else {
      this.isEnriched.set(true); // Always true for other types
    }
  }

  closeModal(): void {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  onSubmit(): void {
    if (this.datasetForm.invalid || !this.selectedDataset()) {
      this.datasetForm.markAllAsTouched();
      return;
    }

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
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de la mise à jour du dataset');
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