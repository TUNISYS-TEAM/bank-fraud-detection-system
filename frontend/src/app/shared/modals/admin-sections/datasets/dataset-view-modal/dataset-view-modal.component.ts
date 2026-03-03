import { Component, EventEmitter, Input, Output, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DatasetService, Dataset, DatasetPreviewResponse } from '../../../../../core/services/dataset.service';
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
}