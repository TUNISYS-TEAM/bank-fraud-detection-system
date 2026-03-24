import { Component, EventEmitter, Input, Output, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DatasetService, Dataset, DatasetPreviewResponse, DatasetStatus, DatasetType, DatasetAnalysisResponse } from '../../../../core/services/dataset.service';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { FeatherModule } from 'angular-feather';

@Component({
  selector: 'app-dataset-view-modal',
  standalone: true,
  imports: [CommonModule, FeatherModule],
  templateUrl: './dataset-view-modal.component.html',
  styleUrls: ['./dataset-view-modal.component.css']
})
export class DatasetViewModalComponent implements OnInit, OnDestroy {
  @Input({ required: true }) dataset!: Dataset;
  @Output() close = new EventEmitter<void>();

  private datasetService = inject(DatasetService);

  isVisible = signal(true);
  isLoading = signal(true);
  previewData = signal<DatasetPreviewResponse | null>(null);
  analysisData = signal<DatasetAnalysisResponse | null>(null);
  errorMessage = signal('');

  readonly DatasetType = DatasetType;

  ngOnInit(): void {
    this.loadData();
  }

  ngOnDestroy(): void {
  }

  loadData(): void {
    this.isLoading.set(true);
    this.errorMessage.set('');

    // Fetch both preview and analysis (if available)
    forkJoin({
      preview: this.datasetService.preview(this.dataset.datasetUuid, 10).pipe(
        catchError(err => {
          console.error('Error loading preview:', err);
          return of(null as any);
        })
      ),
      analysis: this.datasetService.getAnalysis(this.dataset.datasetUuid).pipe(
        catchError(err => {
          console.warn('Analysis not found or error loading analysis:', err);
          return of(null as any);
        })
      )
    }).subscribe({
      next: (results) => {
        if (results.preview) this.previewData.set(results.preview);
        if (results.analysis && results.analysis.statistics) {
          this.analysisData.set(results.analysis);
        }
        
        this.isLoading.set(false);
        if (!results.preview && !results.analysis) {
          this.errorMessage.set('Impossible de charger les données du dataset.');
        }
      },
      error: (err) => {
        console.error('Fatal error loading dataset data:', err);
        this.errorMessage.set('Une erreur fatale est survenue.');
        this.isLoading.set(false);
      }
    });
  }

  // ─── General Helpers ───
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

  getStatusLabel(status: DatasetStatus | null): string {
    if (!status) return 'Brut';
    switch (status) {
      case DatasetStatus.PROCESSED: return 'Traité';
      case DatasetStatus.CLEANED: return 'Nettoyé';
      default: return status;
    }
  }

  getStatusClass(status: DatasetStatus | null): string {
    if (!status) return 'bg-gray-50 text-gray-500 border-gray-100';
    switch (status) {
      case DatasetStatus.PROCESSED: return 'bg-blue-100 text-blue-700 border-blue-200';
      case DatasetStatus.CLEANED: return 'bg-purple-100 text-purple-700 border-purple-200';
      default: return 'bg-gray-100 text-gray-700 border-gray-200';
    }
  }

  getDatasetTypeLabel(type: DatasetType): string {
    switch (type) {
      case DatasetType.ACCOUNTS: return 'Comptes';
      case DatasetType.CLIENTS: return 'Clients';
      case DatasetType.TRANSACTIONS_VAL: return 'Transactions Validation';
      case DatasetType.TRANSACTIONS_TRAIN: return 'Transactions Entraînement';
      case DatasetType.TRANSACTIONS_TRAIN_ENRICHED: return 'Transactions Enrichies';
      default: return type;
    }
  }

  getDatasetTypeClass(type: DatasetType): string {
    switch (type) {
      case DatasetType.ACCOUNTS: return 'bg-amber-100 text-amber-700 border-amber-200';
      case DatasetType.CLIENTS: return 'bg-emerald-100 text-emerald-700 border-emerald-200';
      case DatasetType.TRANSACTIONS_VAL:
      case DatasetType.TRANSACTIONS_TRAIN:
      case DatasetType.TRANSACTIONS_TRAIN_ENRICHED:
        return 'bg-purple-100 text-purple-700 border-purple-200';
      default: return 'bg-gray-100 text-gray-700 border-gray-200';
    }
  }

  getMetricCards() {
    return [
      { 
        label: 'Total Lignes', 
        value: this.formatNumber(this.dataset.rowCount), 
        icon: 'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10', 
        color: 'indigo', 
        bg: 'from-indigo-50 to-white', 
        border: 'border-indigo-100' 
      },
      { 
        label: 'Nb. Colonnes', 
        value: this.previewData()?.columns?.length ?? this.dataset.columnCount ?? 0, 
        icon: 'M9 17V7m0 10a2 2 0 01-2 2H5a2 2 0 01-2-2V7a2 2 0 012-2h2a2 2 0 012 2m0 10a2 2 0 002 2h2a2 2 0 002-2M9 7a2 2 0 012-2h2a2 2 0 012 2m0 10V7m0 10a2 2 0 002 2h2a2 2 0 002-2V7a2 2 0 00-2-2h-2a2 2 0 00-2 2', 
        color: 'blue', 
        bg: 'from-blue-50 to-white', 
        border: 'border-blue-100' 
      },
      { 
        label: 'Taille Fichier', 
        value: this.formatSize(this.dataset.fileSizeMb), 
        icon: 'M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4', 
        color: 'emerald', 
        bg: 'from-emerald-50 to-white', 
        border: 'border-emerald-100' 
      },
      { 
        label: 'Type de Données', 
        value: this.getDatasetTypeLabel(this.dataset.type), 
        icon: 'M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z', 
        color: 'purple', 
        bg: 'from-purple-50 to-white', 
        border: 'border-purple-100' 
      }
    ];
  }
}