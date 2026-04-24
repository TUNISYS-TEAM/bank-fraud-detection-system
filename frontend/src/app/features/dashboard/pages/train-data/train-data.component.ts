import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { forkJoin, of, Subscription } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { environment } from '../../../../../environments/environment';
import { DatasetService, Dataset, ApiResponse, DatasetType, DatasetStatus } from '../../../../core/services/dataset.service';
import { AuthStore } from '../../../../core/store/auth.store';
import { StatsService } from '../../../../core/services/stats.service';
import { KpiCard } from '../../../../shared/cards/cards.component';

// Extended Dataset interface with additional properties
export interface ExtendedDataset extends Dataset {
  statistics?: {
    fraudRate?: number;
    columnCount?: number;
    [key: string]: any;
  };
}

export interface AnalysisResult {
  columns: ColumnInfo[] | null;
  statistics: {
    rowCount: number;
    columnCount: number;
    memoryUsageMb: number;
    analyzedRows?: number;
    isPartialAnalysis?: boolean;
    fraudRate?: number;
    duplicateCount?: number;
    duplicateRate?: number;
    totalMissingValues?: number;
  } | null;
  suggestedFeatures: string[] | null;
  suggestedTarget: string | null;
}

export interface ColumnInfo {
  name: string;
  type: 'NUMERIC' | 'CATEGORICAL' | 'DATE' | 'BINARY' | 'TEXT';
  uniqueCount: number;
  nullRatio: number;
  stats: any;
  isTarget?: boolean;
}

export interface PreviewResponse {
  datasetUuid: string;
  datasetName: string;
  columns: string[];
  rows: Record<string, string>[];
  returnedRows: number;
  limit: number;
  message?: string;
}

export interface CleanResponse {
  datasetUuid: string;
  datasetName: string;
  status: 'COMPLETED' | 'FAILED' | 'IN_PROGRESS';
  jobId: string;
  processedFilePath?: string;
  stats?: {
    originalRows: number;
    cleanedRows: number;
    duplicatesRemoved: number;
    nullRowsRemoved: number;
    reductionPercent: number;
    fraudCount: number;
    fraudRate: number;
  };
}

export interface ExtractBehaviorResponse {
  datasetUuid: string;
  datasetName: string;
  status: 'COMPLETED' | 'FAILED' | 'IN_PROGRESS';
  jobId: string;
  outputPath?: string;
  stats?: {
    totalClients: number;
    featuresGenerated: number;
    processingTimeMs: number;
  };
}

@Component({
  selector: 'app-train-data',
  templateUrl: './train-data.component.html',
  styleUrls: ['./train-data.component.css']
})
export class TrainDataComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private datasetService = inject(DatasetService);
  private statsService = inject(StatsService);
  authStore = inject(AuthStore);
  private statsSubscription?: Subscription;
  public datasetType = DatasetType;
  public datasetStatus = DatasetStatus;

  // KPI Cards
  kpiCards = signal<KpiCard[]>([
    { label: 'Datasets Stockés', value: '...', icon: 'database', color: '#4f46e5' },
    { label: 'Espace Total', value: '...', icon: 'hard-drive', color: '#22c55e' },
    { label: 'Total Lignes', value: '...', icon: 'list', color: '#a855f7' },
    { label: 'Dernier Import', value: '...', icon: 'clock', color: '#f97316' }
  ]);

  // Dataset table state - Use ExtendedDataset
  datasets = signal<ExtendedDataset[]>([]);
  loading = signal(false);
  currentPage = signal(0);
  pageSize = 10;
  totalElements = signal(0);
  totalPages = signal(0);
  searchQuery = signal('');

  // Expanded state for accordion
  expandedDataset = signal<string | null>(null);

  // Analysis states per dataset
  analysisResults = signal<Map<string, AnalysisResult>>(new Map());
  previewDataMap = signal<Map<string, PreviewResponse | null>>(new Map());
  analyzingDatasetId = signal<string | null>(null);
  analysisError = signal<string | null>(null);
  previewLoading = signal(false);

  // Cleaning states
  cleaning = signal<boolean>(false);
  cleaningSuccess = signal<boolean>(false);
  cleaningError = signal<string | null>(null);
  cleanedFilePath = signal<string | null>(null);
  cleaningStats = signal<any | null>(null);
  cleaningDatasetId = signal<string | null>(null);

  // Extract Behavior states
  extractingBehavior = signal<boolean>(false);
  extractingSuccess = signal<boolean>(false); // Keep but don't use in UI
  extractingError = signal<string | null>(null);
  extractingStats = signal<any | null>(null);
  extractingDatasetId = signal<string | null>(null);
  extractedFilePath = signal<string | null>(null);

  // Progress and timing for analysis
  analysisProgress = signal<number>(0);
  analysisElapsedTime = signal<number>(0);
  private analysisProgressInterval: any;
  private analysisStartTime: number = 0;

  // Progress and timing for cleaning
  cleaningProgress = signal<number>(0);
  cleaningElapsedTime = signal<string>('0:00');
  private cleaningProgressInterval: any;
  private cleaningStartTime: number = 0;

  // Progress and timing for extract behavior
  extractingProgress = signal<number>(0);
  extractingElapsedTime = signal<string>('0:00');
  private extractingProgressInterval: any;
  private extractingStartTime: number = 0;

  // Modals
  showCreateModal = signal(false);
  showViewModal = signal(false);
  showUpdateModal = signal(false);
  showDeleteModal = signal(false);
  selectedDataset = signal<ExtendedDataset | null>(null);

  // Computed
  isLoading = computed(() => this.loading());
  hasDatasets = computed(() => this.datasets().length > 0);
  displayRange = computed(() => {
    const start = this.currentPage() * this.pageSize + 1;
    const end = Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());
    return { start, end };
  });

  // Formatage du temps écoulé pour l'analyse
  formattedElapsedTime = computed(() => {
    const seconds = Math.floor(this.analysisElapsedTime() / 1000);
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  });

  ngOnInit(): void {
    this.loadStats();
    this.loadDatasets();

    this.statsSubscription = this.statsService.refreshStats$.subscribe(() => {
      this.loadStats();
    });
  }

  ngOnDestroy(): void {
    this.clearAnalysisProgressInterval();
    this.clearCleaningProgressInterval();
    this.clearExtractingProgressInterval();
    if (this.statsSubscription) {
      this.statsSubscription.unsubscribe();
    }
  }

  // ── KPIs ──────────────────────────────────────────────────────────────
  private loadStats(): void {
    const count$ = this.http.get<number>(
      `${environment.apiUrl}/datasets/count`,
      { params: { types: 'TRANSACTIONS_TRAIN' } }
    ).pipe(catchError(() => of(0)));

    const size$ = this.http.get<number>(
      `${environment.apiUrl}/datasets/total-size`,
      { params: { types: 'TRANSACTIONS_TRAIN' } }
    ).pipe(catchError(() => of(0)));

    forkJoin({ count: count$, size: size$ }).subscribe({
      next: (stats) => {
        this.kpiCards.update(cards => {
          const c = [...cards];
          c[0].value = this.formatNumber(stats.count ?? 0);
          c[1].value = (stats.size ? Number(stats.size).toFixed(2) : '0') + ' MB';
          return c;
        });
      }
    });
  }

  private updateExtraKpis(): void {
    const ds = this.datasets();
    const totalRows = ds.reduce((sum, d) => sum + (d.rowCount || 0), 0);
    const latestDate = ds.length > 0
      ? ds.map(d => d.importedAt).sort().reverse()[0]
      : null;

    this.kpiCards.update(cards => {
      const c = [...cards];
      c[2].value = this.formatNumber(totalRows);
      c[3].value = latestDate ? this.formatDate(latestDate) : 'Aucun';
      return c;
    });
  }

  // ── Datasets ──────────────────────────────────────────────────────────
  loadDatasets(): void {
    this.loading.set(true);

    const params: any = {
      q: this.searchQuery() || undefined,
      page: this.currentPage(),
      size: this.pageSize,
      types: [DatasetType.TRANSACTIONS_TRAIN]
    };

    this.datasetService.list(params).subscribe({
      next: (response: ApiResponse<Dataset>) => {
        const content = response.content;

        // Transform to ExtendedDataset
        const extendedDatasets: ExtendedDataset[] = content.map(ds => ({
          ...ds,
          statistics: (ds as any).statistics || { columnCount: ds.columnCount }
        }));

        this.datasets.set(extendedDatasets);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
        this.updateExtraKpis();

        // Load preview for each dataset
        extendedDatasets.forEach(dataset => {
          this.loadPreview(dataset.datasetUuid);
        });
      },
      error: (error) => {
        console.error('Error loading datasets:', error);
        this.loading.set(false);
      }
    });
  }

  onSearch(event: any): void {
    this.searchQuery.set(event.target.value);
    this.currentPage.set(0);
    this.loadDatasets();
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
    this.loadDatasets();
  }

  // ── Accordion ─────────────────────────────────────────────────────────
  toggleDataset(datasetUuid: string): void {
    const newExpanded = this.expandedDataset() === datasetUuid ? null : datasetUuid;
    this.expandedDataset.set(newExpanded);

    // Load analysis if not already loaded
    if (newExpanded === datasetUuid && !this.analysisResults().has(datasetUuid)) {
      this.checkExistingAnalysis(datasetUuid);
    }
  }

  // ── Analysis Methods ──────────────────────────────────────────────────
  hasValidAnalysisForDataset(datasetUuid: string): boolean {
    const result = this.analysisResults().get(datasetUuid);
    return !!(result && result.columns && result.columns.length > 0);
  }

  getAnalysisForDataset(datasetUuid: string): AnalysisResult | undefined {
    return this.analysisResults().get(datasetUuid);
  }

  previewDataForDataset(datasetUuid: string): PreviewResponse | null | undefined {
    return this.previewDataMap().get(datasetUuid);
  }

  private checkExistingAnalysis(datasetUuid: string): void {
    this.http.get<AnalysisResult>(`${environment.apiUrl}/datasets/${datasetUuid}/analysis`).subscribe({
      next: (result) => {
        const newMap = new Map(this.analysisResults());
        newMap.set(datasetUuid, result);
        this.analysisResults.set(newMap);
      },
      error: (error) => {
        if (error.status !== 404) {
          console.error('Error checking analysis:', error);
        }
      }
    });
  }

  // ── Analysis Endpoint ─────────────────────────────────────────────────
  startAnalysis(datasetUuid: string): void {
    if (!datasetUuid) return;

    this.analyzingDatasetId.set(datasetUuid);
    this.analysisError.set(null);
    this.analysisProgress.set(0);

    this.startAnalysisProgressSimulation();

    this.http.post(`${environment.apiUrl}/datasets/${datasetUuid}/analyze`, {}).subscribe({
      next: () => {
        this.updateAnalysisProgress(50);
        this.pollAnalysis(datasetUuid);
      },
      error: (error) => {
        console.error('Error starting analysis:', error);
        this.stopAnalysisProgress(false);
        this.analyzingDatasetId.set(null);
        this.analysisError.set(error.error?.message || 'Erreur lors du lancement de l\'analyse');
      }
    });
  }

  pollAnalysis(datasetUuid: string): void {
    let attempts = 0;
    const maxAttempts = 60;

    const checkAnalysis = () => {
      this.http.get<AnalysisResult>(`${environment.apiUrl}/datasets/${datasetUuid}/analysis`).subscribe({
        next: (result) => {
          if (result && result.columns && result.columns.length > 0) {
            this.stopAnalysisProgress(true);

            const newMap = new Map(this.analysisResults());
            newMap.set(datasetUuid, result);
            this.analysisResults.set(newMap);

            this.analyzingDatasetId.set(null);
          } else {
            attempts++;
            const progress = Math.min(90, 50 + (attempts * 2));
            this.updateAnalysisProgress(progress);

            if (attempts >= maxAttempts) {
              this.stopAnalysisProgress(false);
              this.analyzingDatasetId.set(null);
              this.analysisError.set('Délai d\'attente dépassé pour l\'analyse');
            } else {
              setTimeout(checkAnalysis, 2000);
            }
          }
        },
        error: (error) => {
          attempts++;
          const progress = Math.min(90, 50 + (attempts * 2));
          this.updateAnalysisProgress(progress);

          if (attempts >= maxAttempts) {
            this.stopAnalysisProgress(false);
            this.analyzingDatasetId.set(null);
            this.analysisError.set('Délai d\'attente dépassé pour l\'analyse');
          } else if (error.status === 404 || error.status === 204) {
            setTimeout(checkAnalysis, 2000);
          } else {
            this.stopAnalysisProgress(false);
            this.analyzingDatasetId.set(null);
            this.analysisError.set('Erreur lors du chargement de l\'analyse');
          }
        }
      });
    };

    setTimeout(checkAnalysis, 1000);
  }

  // ── Preview Methods ───────────────────────────────────────────────────
  private loadPreview(datasetUuid: string): void {
    this.previewLoading.set(true);

    this.datasetService.preview(datasetUuid, 5).pipe(
      catchError(error => {
        console.warn('Preview not available for dataset:', datasetUuid, error.status);
        return of(null);
      })
    ).subscribe({
      next: (preview) => {
        const newMap = new Map(this.previewDataMap());
        newMap.set(datasetUuid, preview);
        this.previewDataMap.set(newMap);
        this.previewLoading.set(false);
      }
    });
  }

  // ── Analysis Progress Methods ─────────────────────────────────────────
  private startAnalysisProgressSimulation(): void {
    this.analysisProgress.set(0);
    this.analysisElapsedTime.set(0);
    this.analysisStartTime = Date.now();

    this.analysisProgressInterval = setInterval(() => {
      this.analysisElapsedTime.set(Date.now() - this.analysisStartTime);

      const currentProgress = this.analysisProgress();
      if (currentProgress < 90) {
        const increment = Math.max(0.5, (90 - currentProgress) / 20);
        this.analysisProgress.update(p => Math.min(90, p + increment));
      }
    }, 200);
  }

  private updateAnalysisProgress(percent: number): void {
    this.analysisProgress.set(percent);
  }

  private stopAnalysisProgress(completed: boolean = true): void {
    if (completed) {
      this.analysisProgress.set(100);
      setTimeout(() => {
        this.clearAnalysisProgressInterval();
      }, 500);
    } else {
      this.clearAnalysisProgressInterval();
    }
  }

  private clearAnalysisProgressInterval(): void {
    if (this.analysisProgressInterval) {
      clearInterval(this.analysisProgressInterval);
      this.analysisProgressInterval = null;
    }
  }

  // ── Clean Endpoint ────────────────────────────────────────────────────
  startCleaning(datasetUuid: string): void {
    if (!datasetUuid) return;

    // Set cleaning state
    this.cleaningDatasetId.set(datasetUuid);
    this.cleaningError.set(null);
    this.cleaningProgress.set(0);

    this.startCleaningProgressSimulation();

    this.http.post<CleanResponse>(`${environment.apiUrl}/datasets/${datasetUuid}/clean`, {}).subscribe({
      next: (response) => {
        this.stopCleaningProgress(true);

        // Update dataset status to CLEANED
        this.datasets.update(datasets => {
          return datasets.map(d => {
            if (d.datasetUuid === datasetUuid) {
              return {
                ...d,
                status: DatasetStatus.CLEANED,
                processedFilePath: response.processedFilePath
              } as ExtendedDataset;
            }
            return d;
          });
        });

        // Reload preview to show updated data
        this.loadPreview(datasetUuid);

        // Reset cleaning state
        this.cleaningDatasetId.set(null);
        this.cleaningProgress.set(0);

        console.log('Nettoyage terminé avec succès');
      },
      error: (err) => {
        this.stopCleaningProgress(false);
        this.cleaningError.set(err.error?.message || 'Erreur lors du nettoyage');
        this.cleaningDatasetId.set(null);
        this.cleaningProgress.set(0);
        console.error('Error cleaning dataset:', err);
      }
    });
  }

  private startCleaningProgressSimulation(): void {
    this.cleaningProgress.set(0);
    this.cleaningElapsedTime.set('0:00');
    this.cleaningStartTime = Date.now();

    this.cleaningProgressInterval = setInterval(() => {
      // Update elapsed time
      const elapsedSeconds = Math.floor((Date.now() - this.cleaningStartTime) / 1000);
      const minutes = Math.floor(elapsedSeconds / 60);
      const seconds = elapsedSeconds % 60;
      this.cleaningElapsedTime.set(`${minutes}:${seconds.toString().padStart(2, '0')}`);

      // Update progress
      const currentProgress = this.cleaningProgress();
      if (currentProgress < 90) {
        const increment = Math.max(0.5, (90 - currentProgress) / 20);
        this.cleaningProgress.update(p => Math.min(90, p + increment));
      }
    }, 200);
  }

  private stopCleaningProgress(completed: boolean = true): void {
    if (completed) {
      this.cleaningProgress.set(100);
      setTimeout(() => {
        this.clearCleaningProgressInterval();
      }, 500);
    } else {
      this.clearCleaningProgressInterval();
    }
  }

  private clearCleaningProgressInterval(): void {
    if (this.cleaningProgressInterval) {
      clearInterval(this.cleaningProgressInterval);
      this.cleaningProgressInterval = null;
    }
  }

  // ── Extract Behavior Endpoint ─────────────────────────────────────────
  // ── Extract Behavior Endpoint ─────────────────────────────────────────
  startExtractBehavior(datasetUuid: string): void {
    if (!datasetUuid) return;

    this.extractingBehavior.set(true);
    this.extractingSuccess.set(false);
    this.extractingError.set(null);
    this.extractingStats.set(null);
    this.extractedFilePath.set(null);
    this.extractingDatasetId.set(datasetUuid);
    this.extractingProgress.set(0);

    this.startExtractingProgressSimulation();

    this.http.post<ExtractBehaviorResponse>(`${environment.apiUrl}/datasets/${datasetUuid}/extract-behavior`, {}).subscribe({
      next: (response) => {
        this.stopExtractingProgress(true);

        this.extractingBehavior.set(false);
        this.extractingSuccess.set(true);
        this.extractedFilePath.set(response.outputPath || null);
        this.extractingStats.set(response.stats || null);

        this.extractingDatasetId.set(null);
        this.extractingProgress.set(0);

        // Reload datasets to show the new enriched dataset with its actual visibility from the database
        this.loadDatasets();

        console.log('Extraction du comportement client terminée avec succès');
      },
      error: (err) => {
        this.stopExtractingProgress(false);
        this.extractingBehavior.set(false);
        this.extractingError.set(err.error?.message || 'Erreur lors de l\'extraction du comportement client');
        this.extractingDatasetId.set(null);
        this.extractingProgress.set(0);
        console.error('Error extracting behavior:', err);
      }
    });
  }

  private startExtractingProgressSimulation(): void {
    this.extractingProgress.set(0);
    this.extractingElapsedTime.set('0:00');
    this.extractingStartTime = Date.now();

    this.extractingProgressInterval = setInterval(() => {
      const elapsedSeconds = Math.floor((Date.now() - this.extractingStartTime) / 1000);
      const minutes = Math.floor(elapsedSeconds / 60);
      const seconds = elapsedSeconds % 60;
      this.extractingElapsedTime.set(`${minutes}:${seconds.toString().padStart(2, '0')}`);

      const currentProgress = this.extractingProgress();
      if (currentProgress < 95) {
        const increment = Math.max(0.2, (95 - currentProgress) / 30);
        this.extractingProgress.update(p => Math.min(95, p + increment));
      }
    }, 300);
  }

  private stopExtractingProgress(completed: boolean = true): void {
    if (completed) {
      this.extractingProgress.set(100);
      setTimeout(() => {
        this.clearExtractingProgressInterval();
      }, 500);
    } else {
      this.clearExtractingProgressInterval();
    }
  }

  private clearExtractingProgressInterval(): void {
    if (this.extractingProgressInterval) {
      clearInterval(this.extractingProgressInterval);
      this.extractingProgressInterval = null;
    }
  }

  // ── Helper methods for status and visibility ──────────────────────────
  getStatusLabel(status: DatasetStatus | null): string {
    if (!status) return 'Non traité';
    switch (status) {
      case DatasetStatus.PROCESSED: return 'Traité';
      case DatasetStatus.CLEANED: return 'Nettoyé';
      default: return status;
    }
  }

  getStatusClass(status: DatasetStatus | null): string {
    if (!status) return 'bg-gray-100 text-gray-600 border-gray-200';
    switch (status) {
      case DatasetStatus.PROCESSED: return 'bg-blue-50 text-blue-700 border-blue-200';
      case DatasetStatus.CLEANED: return 'bg-purple-50 text-purple-700 border-purple-200';
      default: return 'bg-gray-100 text-gray-600 border-gray-200';
    }
  }

  getVisibilityLabel(visible: boolean): string {
    return visible ? 'Visible' : 'Masqué';
  }

  getVisibilityClass(visible: boolean): string {
    return visible
      ? 'bg-green-50 text-green-700 border-green-200'
      : 'bg-gray-100 text-gray-600 border-gray-200';
  }

  // ── Formatters ────────────────────────────────────────────────────────
  formatDate(date: string): string {
    if (!date) return 'N/A';
    return new Date(date).toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  formatSize(mb: number): string {
    if (!mb && mb !== 0) return 'N/A';
    if (mb < 1024) return `${mb.toFixed(2)} MB`;
    return `${(mb / 1024).toFixed(2)} GB`;
  }

  formatNumber(n: number): string {
    if (!n && n !== 0) return '0';
    return new Intl.NumberFormat('fr-FR').format(n);
  }

  getColumnTypeClass(type: string): string {
    const classes: Record<string, string> = {
      'NUMERIC': 'bg-blue-50 text-blue-700 border-blue-200',
      'CATEGORICAL': 'bg-purple-50 text-purple-700 border-purple-200',
      'DATE': 'bg-green-50 text-green-700 border-green-200',
      'BINARY': 'bg-gray-50 text-gray-700 border-gray-200',
      'TEXT': 'bg-amber-50 text-amber-700 border-amber-200'
    };
    return classes[type] || 'bg-gray-50 text-gray-700 border-gray-200';
  }

  getNullRatioClass(ratio: number): string {
    if (ratio === 0) return 'text-green-600';
    if (ratio < 0.05) return 'text-amber-600';
    return 'text-red-600';
  }

  // ── Modals ────────────────────────────────────────────────────────────
  openCreateModal(): void {
    this.showCreateModal.set(true);
  }

  closeCreateModal(): void {
    this.showCreateModal.set(false);
  }

  onDatasetUploaded(): void {
    this.loadStats();
    this.loadDatasets();
    this.closeCreateModal();
  }

  openViewModal(ds: ExtendedDataset): void {
    this.selectedDataset.set(ds);
    this.showViewModal.set(true);
  }

  closeViewModal(): void {
    this.showViewModal.set(false);
    setTimeout(() => this.selectedDataset.set(null), 300);
  }

  openDeleteModal(ds: ExtendedDataset): void {
    this.selectedDataset.set(ds);
    this.showDeleteModal.set(true);
  }

  closeDeleteModal(): void {
    this.showDeleteModal.set(false);
    setTimeout(() => this.selectedDataset.set(null), 300);
  }

  onDatasetDeleted(): void {
    this.loadStats();
    this.loadDatasets();
    this.closeDeleteModal();
  }

  openUpdateModal(ds: ExtendedDataset): void {
    this.selectedDataset.set(ds);
    this.showUpdateModal.set(true);
  }

  closeUpdateModal(): void {
    this.showUpdateModal.set(false);
    setTimeout(() => this.selectedDataset.set(null), 300);
  }

  onDatasetUpdated(): void {
    this.loadDatasets();
    this.closeUpdateModal();
  }

  // ── Pagination ────────────────────────────────────────────────────────
  getPageNumbers(): number[] {
    const total = this.totalPages();
    const current = this.currentPage() + 1;
    const delta = 2;
    const range: number[] = [];
    const result: number[] = [];
    let l: number = 0;

    if (total <= 1) return [1];

    for (let i = 1; i <= total; i++) {
      if (i === 1 || i === total || (i >= current - delta && i <= current + delta)) {
        range.push(i);
      }
    }

    range.forEach((i) => {
      if (l) {
        if (i - l === 2) result.push(l + 1);
        else if (i - l !== 1) result.push(-1);
      }
      result.push(i);
      l = i;
    });

    return result;
  }
}