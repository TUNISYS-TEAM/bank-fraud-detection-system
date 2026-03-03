import { Component, EventEmitter, Output, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { DatasetService } from '../../../../../core/services/dataset.service';
import { FeatherModule } from 'angular-feather';

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

  private fb = inject(FormBuilder);
  private datasetService = inject(DatasetService);

  isVisible = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  selectedFile = signal<File | null>(null);

  datasetForm: FormGroup = this.fb.group({
    datasetName: ['', [Validators.required, Validators.minLength(3)]]
  });

  onFileSelected(event: any): void {
    const file = event.target.files[0];
    if (file) {
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
    const file = this.selectedFile()!;

    this.datasetService.upload(file, name).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.datasetUploaded.emit();
        this.closeModal();
      },
      error: (err: any) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Erreur lors de l\'importation du dataset');
        console.error('Error uploading dataset:', err);
      }
    });
  }
}