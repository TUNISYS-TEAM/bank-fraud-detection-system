import { Component, EventEmitter, Input, Output, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DatasetService, Dataset, DatasetPreviewResponse, DatasetStatus } from '../../../../../core/services/dataset.service';
import { FeatherModule } from 'angular-feather';

@Component({
  selector: 'app-dataset-view-modal',
  standalone: true,
  imports: [CommonModule, FeatherModule],
  templateUrl: './dataset-view-modal.component.html',
  styleUrls: ['./dataset-view-modal.component.css']
})
export class DatasetViewModalComponent implements OnInit {
  @Input({ required: true }) dataset!: Dataset;
  @Output() close = new EventEmitter<void>();

  private datasetService = inject(DatasetService);

  isVisible = signal(true);
  isLoading = signal(true);
  previewData = signal<DatasetPreviewResponse | null>(null);
  errorMessage = signal('');

  ngOnInit(): void {
    this.loadPreview();
  }

  loadPreview(): void {
    this.isLoading.set(true);
    this.datasetService.preview(this.dataset.datasetUuid, 10).subscribe({
      next: (data: DatasetPreviewResponse) => {
        this.previewData.set(data);
        this.isLoading.set(false);
      },
      error: (err: any) => {
        console.error('Error loading preview:', err);
        this.errorMessage.set('Impossible de charger l\'aperçu du dataset.');
        this.isLoading.set(false);
      }
    });
  }

  closeModal(): void {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  formatSize(mb: number): string {
    if (mb < 1024) return `${mb.toFixed(2)} MB`;
    return `${(mb / 1024).toFixed(2)} GB`;
  }

  formatNumber(n: number): string {
    return new Intl.NumberFormat('fr-FR').format(n);
  }

  // Helper to get status label
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
    if (!status) return 'bg-gray-100 text-gray-600 border-gray-200';
    switch (status) {
      case DatasetStatus.PROCESSED: return 'bg-blue-50 text-blue-700 border-blue-200';
      case DatasetStatus.CLEANED: return 'bg-purple-50 text-purple-700 border-purple-200';
      default: return 'bg-gray-100 text-gray-600 border-gray-200';
    }
  }

  // Helper to get visibility label
  getVisibilityLabel(visible: boolean): string {
    return visible ? 'Visible' : 'Masqué';
  }

  // Helper to get visibility color class
  getVisibilityClass(visible: boolean): string {
    return visible 
      ? 'bg-green-50 text-green-700 border-green-200' 
      : 'bg-gray-100 text-gray-600 border-gray-200';
  }
}