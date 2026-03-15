import { Component, EventEmitter, Output, Input, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { DatasetService, DatasetType } from '../../../../../core/services/dataset.service';
import { AuthStore } from '../../../../../core/store/auth.store';
import { FeatherModule } from 'angular-feather';
import { switchMap, of, take } from 'rxjs';

@Component({
  selector: 'app-dataset-create-modal',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FeatherModule],
  templateUrl: './dataset-create-modal.component.html',
  styleUrls: ['./dataset-create-modal.component.css']
})
export class DatasetCreateModalComponent {
  @Output() close = new EventEmitter<void>();
  @Output() datasetUploaded = new EventEmitter<void>();
  @Input() allowedTypes?: DatasetType[];

  private fb = inject(FormBuilder);
  private datasetService = inject(DatasetService);

  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  showOverwriteWarning = signal(false);
  isDragging = signal(false);
  selectedFile = signal<File | null>(null);
  private authStore = inject(AuthStore);
  datasetTypes = computed(() => {
    if (this.allowedTypes && this.allowedTypes.length > 0) {
      return this.allowedTypes;
    }
    if (this.authStore.isAdmin()) {
      return [DatasetType.CLIENTS, DatasetType.ACCOUNTS, DatasetType.TRANSACTIONS_VAL];
    }
    if (this.authStore.isManager()) {
      return [DatasetType.TRANSACTIONS_TRAIN];
    }
    return Object.values(DatasetType);
  });

  datasetForm: FormGroup = this.fb.group({
    datasetName: ['', [Validators.required, Validators.minLength(3)]],
    type: ['', [Validators.required]]
  });

  constructor() {
    // We update the type in ngOnInit or whenever inputs are available if using newer Angular
    // But for now let's set a default and we will patch it if allowedTypes is provided
  }

  ngOnInit() {
    const defaultType = (this.allowedTypes && this.allowedTypes.length > 0)
      ? this.allowedTypes[0]
      : (this.authStore.isAdmin() ? DatasetType.TRANSACTIONS_VAL : DatasetType.TRANSACTIONS_TRAIN);
    
    this.datasetForm.patchValue({ type: defaultType });
  }

  onFileSelected(event: any): void {
    const file = event.target.files[0];
    if (file) {
      this.handleFile(file);
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(false);

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this.handleFile(files[0]);
    }
  }

  private handleFile(file: File): void {
    if (file.type !== 'text/csv' && !file.name.endsWith('.csv')) {
      this.errorMessage.set('Veuillez sélectionner un fichier CSV valide.');
      this.selectedFile.set(null);
      return;
    }
    this.selectedFile.set(file);
    this.errorMessage.set('');

    // Auto-fill dataset name if empty
    if (!this.datasetForm.get('datasetName')?.value) {
      const fileName = file.name.replace(/\.[^/.]+$/, "");
      this.datasetForm.patchValue({ datasetName: fileName });
    }
  }

  closeModal(): void {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  onSubmit(): void {
    if (this.datasetForm.invalid || !this.selectedFile()) {
      this.errorMessage.set('Veuillez remplir tous les champs et sélectionner un fichier.');
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    const name = this.datasetForm.get('datasetName')?.value;
    const type = this.datasetForm.get('type')?.value;
    const file = this.selectedFile()!;

    // Si c'est CLIENTS ou ACCOUNTS, vérifier si un dataset existe déjà
    if ((type === DatasetType.CLIENTS || type === DatasetType.ACCOUNTS) && !this.showOverwriteWarning()) {
      this.datasetService.list({ type, page: 0, size: 1 }).pipe(
        take(1)
      ).subscribe({
        next: (response) => {
          if (response.totalElements > 0) {
            this.showOverwriteWarning.set(true);
            this.isLoading.set(false);
          } else {
            this.executeUpload(file, name, type);
          }
        },
        error: (err) => this.handleError(err)
      });
    } else {
      this.executeUpload(file, name, type);
    }
  }

  confirmOverwrite(): void {
    this.showOverwriteWarning.set(false);
    this.onSubmit();
  }

  cancelOverwrite(): void {
    this.showOverwriteWarning.set(false);
    this.isLoading.set(false);
  }

  private executeUpload(file: File, name: string, type: DatasetType): void {
    this.isLoading.set(true);
    this.datasetService.upload(file, name, type).subscribe({
      next: () => this.handleSuccess(),
      error: (err) => this.handleError(err)
    });
  }

  private handleSuccess(): void {
    this.isLoading.set(false);
    this.datasetUploaded.emit();
    this.closeModal();
  }

  private handleError(err: any): void {
    this.isLoading.set(false);
    this.errorMessage.set(err.error?.message || 'Erreur lors de l\'importation du dataset');
    console.error('Error uploading dataset:', err);
  }
}