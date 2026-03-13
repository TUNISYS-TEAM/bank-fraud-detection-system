import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../environments/environment';
import { AuthStore } from '../../../../core/store/auth.store';
import { KpiCard } from '../../../../shared/cards/cards.component';

export interface Dataset {
  datasetUuid: string;
  datasetCode: string;
  datasetName: string;
  fileSizeMb: number;
  rowCount: number;
  status: 'ACTIVE' | 'PROCESSED' | 'INACTIVE';
  importedAt: string;
  filePath?: string;
  processedFilePath?: string;
  featureColumns?: any;
  statistics?: any;
}

export interface ApiResponse {
  content: Dataset[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
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

@Component({
  selector: 'app-import-data',
  templateUrl: './import-data.component.html',
  styleUrls: ['./import-data.component.css']
})
export class ImportDataComponent implements OnInit {
  private http = inject(HttpClient);
  authStore = inject(AuthStore);

  // États pour la liste des datasets
  allDatasets = signal<Dataset[]>([]);
  loading = signal(false);
  currentPage = signal(0);
  pageSize = 10;
  totalElements = signal(0);
  totalPages = signal(0);
  searchQuery = signal('');

  // Computed pour filtrer les datasets ACTIVE et PROCESSED
  datasets = computed(() => 
    this.allDatasets().filter(d => d.status === 'ACTIVE' || d.status === 'PROCESSED')
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

  // États pour l'entraînement (gardés pour compatibilité mais non utilisés)
  training = signal<boolean>(false);
  trainingSuccess = signal<boolean>(false);
  modelName = signal<string>('');

  // État pour le nettoyage
  cleaning = signal<boolean>(false);
  cleaningSuccess = signal<boolean>(false);
  cleaningError = signal<string | null>(null);
  cleanedFilePath = signal<string | null>(null);
  cleaningStats = signal<any | null>(null);
  
  // Progression et chrono pour le nettoyage
  cleaningProgress = signal<number>(0);
  cleaningElapsedTime = signal<string>('0:00');
  private cleaningProgressInterval: any;
  private cleaningStartTime: number = 0;

  // Computed pour vérifier si le dataset a une analyse valide
  hasValidAnalysis = computed(() => {
    const result = this.analysisResult();
    return result && result.columns && result.columns.length > 0;
  });

  // Computed pour vérifier si le dataset est traité
  isProcessed = computed(() => 
    this.selectedDataset()?.status === 'PROCESSED'
  );

  // Cartes KPI dynamiques
  kpiCards = signal<KpiCard[]>([
    {
      label: 'Lignes Importées',
      value: 'Chargement...',
      icon: 'database',
      color: '#4f46e5'
    },
    {
      label: 'Taux de fraude',
      value: 'Chargement...',
      icon: 'alert-circle',
      trend: 'Anomalies détectées',
      trendUp: false,
      color: '#ef4444'
    },
    {
      label: 'Doublons Détectés',
      value: 'Chargement...',
      icon: 'copy',
      trend: 'À nettoyer',
      trendUp: false,
      color: '#f59e0b'
    },
    {
      label: 'Valeurs Manquantes',
      value: 'Chargement...',
      icon: 'help-circle',
      trend: 'Total',
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
    this.clearCleaningProgressInterval();
  }

  // ==================== DATASET METHODS ====================

  loadDatasets(): void {
    this.loading.set(true);
    let url = `${environment.apiUrl}/datasets?page=${this.currentPage()}&size=${this.pageSize}`;

    if (this.searchQuery()) {
      url += `&q=${encodeURIComponent(this.searchQuery())}`;
    }

    this.http.get<ApiResponse>(url).subscribe({
      next: (response) => {
        this.allDatasets.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
      },
      error: (error) => {
        console.error('Error loading datasets:', error);
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

  // ==================== CLEANING METHODS ====================

  startCleaning(datasetUuid: string): void {
    if (!datasetUuid) return;
    
    this.cleaning.set(true);
    this.cleaningSuccess.set(false);
    this.cleaningError.set(null);
    this.cleanedFilePath.set(null);
    this.cleaningStats.set(null);
    
    // Démarrer la progression simulée pour le nettoyage
    this.startCleaningProgressSimulation();
    
    this.http.post<CleanResponse>(`${environment.apiUrl}/datasets/${datasetUuid}/clean`, {}).subscribe({
      next: (response) => {
        // Arrêter la progression simulée
        this.stopCleaningProgress(true);
        
        this.cleaning.set(false);
        this.cleaningSuccess.set(true);
        this.cleanedFilePath.set(response.processedFilePath || null);
        this.cleaningStats.set(response.stats || null);
        
        // Recharger le dataset pour mettre à jour le statut
        this.loadDatasets();
        
        // Si c'est le dataset sélectionné, mettre à jour son statut
        if (this.selectedDataset()?.datasetUuid === datasetUuid) {
          this.selectedDataset.update(ds => {
            if (ds) {
              ds.status = 'PROCESSED';
              ds.processedFilePath = response.processedFilePath;
            }
            return ds;
          });
          
          // Recharger l'aperçu après mise à jour du statut
          this.loadPreview(datasetUuid);
        }
        
        console.log('Nettoyage terminé avec succès');
      },
      error: (err) => {
        this.stopCleaningProgress(false);
        this.cleaning.set(false);
        this.cleaningError.set(err.error?.message || 'Erreur lors du nettoyage');
        console.error('Error cleaning dataset:', err);
      }
    });
  }

  // ==================== CLEANING PROGRESS METHODS ====================

  private startCleaningProgressSimulation(): void {
    this.cleaningProgress.set(0);
    this.cleaningElapsedTime.set('0:00');
    this.cleaningStartTime = Date.now();

    this.cleaningProgressInterval = setInterval(() => {
      // Mettre à jour le temps écoulé
      const elapsedSeconds = Math.floor((Date.now() - this.cleaningStartTime) / 1000);
      const minutes = Math.floor(elapsedSeconds / 60);
      const seconds = elapsedSeconds % 60;
      this.cleaningElapsedTime.set(`${minutes}:${seconds.toString().padStart(2, '0')}`);

      // Simuler la progression (max 95% jusqu'à la réponse)
      const currentProgress = this.cleaningProgress();
      if (currentProgress < 95) {
        const increment = Math.max(0.2, (95 - currentProgress) / 30);
        this.cleaningProgress.update(p => Math.min(95, p + increment));
      }
    }, 300);
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
    this.cleaningSuccess.set(false);
    this.cleaningError.set(null);
    this.cleanedFilePath.set(dataset.processedFilePath || null);
    this.cleaningStats.set(null);
    this.modelName.set(`Model_${dataset.datasetName}_${new Date().getTime()}`);
    this.trainingSuccess.set(false);

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
        this.analysisResult.set(result);
        this.updateKpiCardsFromAnalysis(result);
      },
      error: (error) => {
        if (error.status === 404) {
          this.analysisResult.set({
            columns: null,
            statistics: null,
            suggestedFeatures: null,
            suggestedTarget: null
          });
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
        newCards[1] = {
          ...newCards[1],
          value: `${stats.fraudRate.toFixed(2)}%`,
          trend: stats.fraudRate > 2 ? 'Élevé' : 'Normal'
        };
      }

      if (stats.duplicateCount !== undefined) {
        newCards[2] = {
          ...newCards[2],
          value: this.formatNumber(stats.duplicateCount),
          trend: stats.duplicateRate !== undefined
            ? `${stats.duplicateRate.toFixed(1)}% des lignes`
            : 'À nettoyer'
        };
      }

      if (stats.totalMissingValues !== undefined) {
        newCards[3] = {
          ...newCards[3],
          value: this.formatNumber(stats.totalMissingValues),
          trend: stats.totalMissingValues === 0 ? 'Aucune' : 'À traiter'
        };
      }

      return newCards;
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

  // ==================== TRAINING METHODS (gardés pour compatibilité) ====================

  startTraining(): void {
    console.log('Training method kept for compatibility');
  }

  exportCsv(): void {
    if (!this.selectedDataset()) return;
    console.log('Export CSV');
  }

  showMore(): void {
    if (!this.selectedDataset()) return;
    this.loadPreview(this.selectedDataset()!.datasetUuid);
  }

  backToList(): void {
    this.selectedDataset.set(null);
    this.analysisResult.set(null);
    this.previewData.set(null);
    this.trainingSuccess.set(false);
    this.analysisError.set(null);
    this.analyzingDatasetId.set(null);
    this.cleaningSuccess.set(false);
    this.cleaningError.set(null);
    this.cleanedFilePath.set(null);
    this.cleaningStats.set(null);
    this.modelName.set('');
    this.clearAnalysisProgressInterval();
    this.clearCleaningProgressInterval();

    // Réinitialiser les KPIs
    this.kpiCards.set([
      {
        label: 'Lignes Importées',
        value: 'Chargement...',
        icon: 'database',
        color: '#4f46e5'
      },
      {
        label: 'Taux de fraude',
        value: 'Chargement...',
        icon: 'alert-circle',
        trend: 'Anomalies détectées',
        trendUp: false,
        color: '#ef4444'
      },
      {
        label: 'Doublons Détectés',
        value: 'Chargement...',
        icon: 'copy',
        trend: 'À nettoyer',
        trendUp: false,
        color: '#f59e0b'
      },
      {
        label: 'Valeurs Manquantes',
        value: 'Chargement...',
        icon: 'help-circle',
        trend: 'Total',
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

  getStatusClass(status: string): string {
    switch(status) {
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

  getStatusText(status: string): string {
    switch(status) {
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