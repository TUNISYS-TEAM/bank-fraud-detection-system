import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../environments/environment';
import { AuthStore } from '../../../../core/store/auth.store';
import { AuthService } from '../../../../core/services/auth.service';
import { DatasetService, Dataset, ApiResponse } from '../../../../core/services/dataset.service';
import { KpiCard } from '../../../../shared/cards/cards.component';

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

export interface PreviewResponse {
  datasetUuid: string;
  datasetName: string;
  columns: string[];
  rows: any[];
  returnedRows: number;
  limit: number;
  message?: string;
}

export interface ColumnInfo {
  name: string;
  type: 'NUMERIC' | 'CATEGORICAL' | 'DATE' | 'BINARY' | 'TEXT';
  uniqueCount: number;
  nullRatio: number;
  stats: any;
  isTarget?: boolean;
}

@Component({
  selector: 'app-import-data',
  templateUrl: './import-data.component.html',
  styleUrls: ['./import-data.component.css']
})
export class ImportDataComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private route = inject(ActivatedRoute);
  private authService = inject(AuthService);
  private datasetService = inject(DatasetService);
  authStore = inject(AuthStore);

  // États pour la liste des datasets
  allDatasets = signal<Dataset[]>([]);
  loading = signal(false);
  currentPage = signal(0);
  pageSize = 10;
  totalElements = signal(0);
  totalPages = signal(0);
  searchQuery = signal('');

  // Computed pour filtrer les datasets PROCESSED uniquement
  datasets = computed(() =>
    this.allDatasets().filter(d => d.status === 'PROCESSED')
  );

  // États pour l'analyse
  analyzingDatasetId = signal<string | null>(null);
  analysisResult = signal<AnalysisResult | null>(null);
  previewData = signal<PreviewResponse | null>(null);
  selectedDataset = signal<Dataset | null>(null);
  analysisError = signal<string | null>(null);
  previewLoading = signal(false);

  // Progression et chrono pour l'analyse
  analysisProgress = signal<number>(0);
  analysisElapsedTime = signal<number>(0);
  private analysisProgressInterval: any;
  private analysisStartTime: number = 0;

  // Computed pour vérifier si le dataset a une analyse valide
  hasValidAnalysis = computed(() => {
    const result = this.analysisResult();
    // Vérification plus stricte
    return result !== null &&
      result.columns !== null &&
      Array.isArray(result.columns) &&
      result.columns.length > 0;
  });

  // Computed pour savoir si l'analyse est en cours
  isAnalyzing = computed(() => {
    return this.analyzingDatasetId() === this.selectedDataset()?.datasetUuid;
  });

  // Cartes KPI dynamiques
  kpiCards = signal<KpiCard[]>([
    {
      label: 'Lignes Importées',
      value: 'Chargement...',
      icon: 'database',
      trend: 'Vérifiées',
      trendIcon: 'check',
      trendUp: true,
      color: '#4f46e5'
    },
    {
      label: 'Taux de fraude',
      value: 'Chargement...',
      icon: 'alert-circle',
      trend: 'Anomalies détectées',
      trendIcon: 'check',
      trendUp: true,
      color: '#ef4444'
    },
    {
      label: 'Doublons Détectés',
      value: 'Chargement...',
      icon: 'copy',
      trend: 'À nettoyer',
      trendIcon: 'check',
      trendUp: true,
      color: '#f59e0b'
    },
    {
      label: 'Valeurs Manquantes',
      value: 'Chargement...',
      icon: 'help-circle',
      trend: 'Total',
      trendIcon: 'check',
      trendUp: true,
      color: '#10b981'
    }
  ]);

  // Computed values
  displayRange = computed(() => {
    const start = this.currentPage() * this.pageSize + 1;
    const end = Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());
    return { start, end };
  });

  hasDatasets = computed(() => this.datasets().length > 0);
  isLoading = computed(() => this.loading());

  // Formatage du temps écoulé pour l'analyse
  formattedElapsedTime = computed(() => {
    const seconds = Math.floor(this.analysisElapsedTime() / 1000);
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  });

  ngOnInit(): void {
    this.loadDatasets();
  }

  ngOnDestroy(): void {
    this.clearAnalysisProgressInterval();
  }

  // ==================== DATASET METHODS ====================

  loadDatasets(): void {
    this.loading.set(true);

    const userRole = this.authStore.userRole();
    const teamContext: { managerId?: number } = this.authService.getTeamContext();

    const params: any = {
      q: this.searchQuery() || undefined,
      page: this.currentPage(),
      size: this.pageSize,
      // Analysts see enriched data, Managers see raw training data
      types: userRole === 'ANALYST' ? ['TRANSACTIONS_TRAIN_ENRICHED'] : ['TRANSACTIONS_TRAIN']
    };

    if (userRole === 'ANALYST') {
      params.visibility = true;
    }

    if (teamContext.managerId !== undefined) {
      params.managerId = teamContext.managerId;
    }

    this.datasetService.list(params).subscribe({
      next: (response: ApiResponse<Dataset>) => {
        this.allDatasets.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);

        // Auto-select dataset if coming from query param
        const targetUuid = this.route.snapshot.queryParamMap.get('datasetUuid');
        if (targetUuid && !this.selectedDataset()) {
          const targetDataset = response.content.find((d: Dataset) => d.datasetUuid === targetUuid);
          if (targetDataset) {
            this.selectDataset(targetDataset);
          }
        }
      },
      error: (error: any) => {
        console.error('Error loading training datasets:', error);
        this.loading.set(false);
      }
    });
  }

  onSearch(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.searchQuery.set(input.value);
    this.currentPage.set(0);
    this.loadDatasets();
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
    this.loadDatasets();
  }

  // ==================== ANALYSIS PROGRESS METHODS ====================

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

  // ==================== SELECTION METHODS ====================

  selectDataset(dataset: Dataset): void {
    this.selectedDataset.set(dataset);
    this.analysisError.set(null);
    this.analysisResult.set(null);
    this.previewData.set(null);
    this.analyzingDatasetId.set(null); // Important: reset analyzing state

    // Mettre à jour les KPIs avec les données du dataset
    this.updateKpiCards(dataset);

    // TOUJOURS charger le preview dès la sélection
    this.loadPreview(dataset.datasetUuid);

    // Vérifier si l'analyse existe déjà en base
    this.checkExistingAnalysis(dataset.datasetUuid);
  }

  private checkExistingAnalysis(datasetUuid: string): void {
    this.http.get<AnalysisResult>(`${environment.apiUrl}/datasets/${datasetUuid}/analysis`).subscribe({
      next: (result) => {
        console.log('Analysis result received:', result);
        this.analysisResult.set(result);
        this.updateKpiCardsFromAnalysis(result);
      },
      error: (error) => {
        if (error.status === 404) {
          console.log('No analysis found for dataset');
          this.analysisResult.set(null);
        } else {
          console.error('Error checking analysis:', error);
          this.analysisError.set('Erreur lors de la vérification de l\'analyse');
        }
      }
    });
  }

  private updateKpiCards(dataset: Dataset): void {
    this.kpiCards.update(cards => {
      const newCards = [...cards];
      newCards[0] = { ...newCards[0], value: this.formatNumber(dataset.rowCount) };
      return newCards;
    });
  }

  private updateKpiCardsFromAnalysis(result: AnalysisResult): void {
    if (!result || !result.statistics) return;

    const stats = result.statistics;

    this.kpiCards.update(cards => {
      const newCards = [...cards];

      if (stats.rowCount) {
        newCards[0] = { ...newCards[0], value: this.formatNumber(stats.rowCount) };
      }

      if (stats.fraudRate !== undefined) {
        const isNormal = stats.fraudRate <= 2;
        newCards[1] = {
          ...newCards[1],
          value: `${stats.fraudRate.toFixed(2)}%`,
          trend: isNormal ? 'Normal' : 'Élevé',
          trendIcon: isNormal ? 'check' : 'alert-circle',
          trendUp: isNormal
        };
      }

      if (stats.duplicateCount !== undefined) {
        const isClean = stats.duplicateCount === 0;
        newCards[2] = {
          ...newCards[2],
          value: this.formatNumber(stats.duplicateCount),
          trend: stats.duplicateRate !== undefined
            ? `${stats.duplicateRate.toFixed(1)}% des lignes`
            : (isClean ? 'Aucun doublon' : 'À nettoyer'),
          trendIcon: isClean ? 'check' : 'copy',
          trendUp: isClean
        };
      }

      if (stats.totalMissingValues !== undefined) {
        const isComplete = stats.totalMissingValues === 0;
        newCards[3] = {
          ...newCards[3],
          value: this.formatNumber(stats.totalMissingValues),
          trend: isComplete ? 'Aucune' : 'À traiter',
          trendIcon: isComplete ? 'check' : 'help-circle',
          trendUp: isComplete
        };
      }

      return newCards;
    });
  }

  exportDataset(dataset: Dataset): void {
    const url = `${environment.apiUrl}/datasets/${dataset.datasetUuid}/download`;
    
    this.http.get(url, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        const link = document.createElement('a');
        link.href = window.URL.createObjectURL(blob);
        link.download = `${dataset.datasetName}.csv`;
        link.click();
        window.URL.revokeObjectURL(link.href);
      },
      error: (error) => {
        console.error('Error exporting dataset:', error);
      }
    });
  }

  // ==================== PREVIEW METHODS ====================

  private loadPreview(datasetUuid: string): void {
    this.previewLoading.set(true);
    this.previewData.set(null); // Réinitialiser les données précédentes

    this.http.get<PreviewResponse>(`${environment.apiUrl}/datasets/${datasetUuid}/preview?limit=5`).subscribe({
      next: (preview) => {
        console.log('Preview data received:', preview);
        this.previewData.set(preview);
        this.previewLoading.set(false);
      },
      error: (error) => {
        console.error('Error loading preview:', error);
        this.previewLoading.set(false);
        this.previewData.set(null);
      }
    });
  }

  getPreviewColumns(): string[] {
    return this.previewData()?.columns || [];
  }

  getPreviewRows(): any[] {
    return this.previewData()?.rows || [];
  }

  // ==================== ANALYSIS METHODS ====================

  startAnalysis(datasetUuid: string): void {
    this.analyzingDatasetId.set(datasetUuid);
    this.analysisError.set(null);
    this.analysisResult.set(null);

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
        this.analysisError.set('Erreur lors du lancement de l\'analyse');
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
            this.analysisResult.set(result);
            this.updateKpiCardsFromAnalysis(result);
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

  backToList(): void {
    this.selectedDataset.set(null);
    this.analysisResult.set(null);
    this.previewData.set(null);
    this.analysisError.set(null);
    this.analyzingDatasetId.set(null);
    this.clearAnalysisProgressInterval();

    // Réinitialiser les KPIs
    this.kpiCards.set([
      {
        label: 'Lignes Importées',
        value: 'Chargement...',
        icon: 'check-circle',
        trend: 'Vérifiées',
        trendIcon: 'check',
        trendUp: true,
        color: '#4f46e5'
      },
      {
        label: 'Taux de fraude',
        value: 'Chargement...',
        icon: 'alert-circle',
        trend: 'Anomalies détectées',
        trendIcon: 'check',
        trendUp: true,
        color: '#ef4444'
      },
      {
        label: 'Doublons Détectés',
        value: 'Chargement...',
        icon: 'copy',
        trend: 'À nettoyer',
        trendIcon: 'check',
        trendUp: true,
        color: '#f59e0b'
      },
      {
        label: 'Valeurs Manquantes',
        value: 'Chargement...',
        icon: 'help-circle',
        trend: 'Total',
        trendIcon: 'check',
        trendUp: true,
        color: '#10b981'
      }
    ]);
  }

  // ==================== UTILITY METHODS ====================

  formatDate(date: string | null): string {
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
    if (!mb) return 'N/A';
    if (mb < 1024) return `${mb.toFixed(2)} MB`;
    return `${(mb / 1024).toFixed(2)} GB`;
  }

  formatNumber(n: number): string {
    if (!n) return '0';
    return new Intl.NumberFormat('fr-FR').format(n);
  }

  getStatusClass(status: string | null | undefined): string {
    if (!status) return 'bg-gray-50 text-gray-500 border-gray-100';
    switch (status) {
      case 'ACTIVE':
        return 'bg-green-50 text-green-600 border-green-100';
      case 'PROCESSED':
        return 'bg-blue-50 text-blue-600 border-blue-100';
      case 'INACTIVE':
        return 'bg-gray-50 text-gray-500 border-gray-100';
      default:
        return 'bg-gray-50 text-gray-500 border-gray-100';
    }
  }

  getStatusText(status: string | null | undefined): string {
    if (!status) return 'NON TRAITÉ';
    switch (status) {
      case 'ACTIVE':
        return 'ACTIF';
      case 'PROCESSED':
        return 'TRAITÉ';
      case 'INACTIVE':
        return 'INACTIF';
      default:
        return status;
    }
  }

  getColumnTypeIcon(type: string): string {
    const icons: Record<string, string> = {
      'NUMERIC': 'hash',
      'CATEGORICAL': 'tag',
      'DATE': 'calendar',
      'BINARY': 'check-circle',
      'TEXT': 'file-text'
    };
    return icons[type] || 'help-circle';
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
}