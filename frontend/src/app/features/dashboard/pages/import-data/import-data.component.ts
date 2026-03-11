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
  status: 'ACTIVE' | 'INACTIVE';
  importedAt: string;
  filePath?: string;
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
    fraudRate?: number;           // Nouveau
    duplicateCount?: number;      // Nouveau
    duplicateRate?: number;       // Nouveau
    totalMissingValues?: number;  // Nouveau
  } | null;
  suggestedFeatures: string[] | null;
  suggestedTarget: string | null;
}

export interface PreviewResponse {
  columns: string[];
  rows: any[];
  returnedRows: number;
  limit: number;
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

  // Computed pour filtrer uniquement les datasets ACTIVE
  datasets = computed(() =>
    this.allDatasets().filter(d => d.status === 'ACTIVE')
  );

  // États pour l'analyse
  analyzingDatasetId = signal<string | null>(null);
  analysisResult = signal<AnalysisResult | null>(null);
  previewData = signal<PreviewResponse | null>(null);
  selectedDataset = signal<Dataset | null>(null);
  analysisError = signal<string | null>(null);
  previewLoading = signal(false);

  // Progression et chrono
  analysisProgress = signal<number>(0);
  elapsedTime = signal<number>(0);
  private progressInterval: any;
  private startTime: number = 0;

  // États pour l'entraînement
  training = false;
  trainingSuccess = false;
  modelName = '';

  // Computed pour vérifier si le dataset a une analyse valide
  hasValidAnalysis = computed(() => {
    const result = this.analysisResult();
    return result && result.columns && result.columns.length > 0;
  });

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

  // Formatage du temps écoulé
  formattedElapsedTime = computed(() => {
    const seconds = Math.floor(this.elapsedTime() / 1000);
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  });

  ngOnInit(): void {
    this.loadDatasets();
  }

  ngOnDestroy(): void {
    this.clearProgressInterval();
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

  // ==================== PROGRESS METHODS ====================

  private startProgressSimulation(): void {
    this.analysisProgress.set(0);
    this.elapsedTime.set(0);
    this.startTime = Date.now();

    this.progressInterval = setInterval(() => {
      this.elapsedTime.set(Date.now() - this.startTime);

      const currentProgress = this.analysisProgress();
      if (currentProgress < 90) {
        const increment = Math.max(0.5, (90 - currentProgress) / 20);
        this.analysisProgress.update(p => Math.min(90, p + increment));
      }
    }, 200);
  }

  private updateProgress(percent: number): void {
    this.analysisProgress.set(percent);
  }

  private stopProgress(completed: boolean = true): void {
    if (completed) {
      this.analysisProgress.set(100);
      setTimeout(() => {
        this.clearProgressInterval();
      }, 500);
    } else {
      this.clearProgressInterval();
    }
  }

  private clearProgressInterval(): void {
    if (this.progressInterval) {
      clearInterval(this.progressInterval);
      this.progressInterval = null;
    }
  }

  // ==================== SELECTION METHODS ====================

  selectDataset(dataset: Dataset): void {
    this.selectedDataset.set(dataset);
    this.analysisError.set(null);
    this.analysisResult.set(null);
    this.previewData.set(null);
    this.modelName = `Model_${dataset.datasetName}_${new Date().getTime()}`;
    this.trainingSuccess = false;

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
        // Analyse trouvée (même si elle est null)
        this.analysisResult.set(result);
        // Mettre à jour les KPIs avec les résultats de l'analyse
        this.updateKpiCardsFromAnalysis(result);
      },
      error: (error) => {
        if (error.status === 404) {
          // Analyse non trouvée, on initialise avec null
          this.analysisResult.set({
            columns: null,
            statistics: null,
            suggestedFeatures: null,
            suggestedTarget: null
          });
        } else {
          // Autre erreur
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
    if (!result) return;

    // Vérifier que statistics n'est pas null
    const stats = result.statistics;
    if (!stats) return;

    this.kpiCards.update(cards => {
      const newCards = [...cards];

      // Lignes importées
      if (stats.rowCount) {
        newCards[0] = {
          ...newCards[0],
          value: this.formatNumber(stats.rowCount)
        };
      }

      // Taux de fraude
      if (stats.fraudRate !== undefined) {
        newCards[1] = {
          ...newCards[1],
          value: `${stats.fraudRate}%`,
          trend: stats.fraudRate > 2 ? 'Élevé' : 'Normal'
        };
      }

      // Doublons
      if (stats.duplicateCount !== undefined) {
        newCards[2] = {
          ...newCards[2],
          value: this.formatNumber(stats.duplicateCount),
          trend: stats.duplicateRate !== undefined
            ? `${stats.duplicateRate}% des lignes`
            : 'À nettoyer'
        };
      }

      // Valeurs manquantes
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
    this.http.get<PreviewResponse>(`${environment.apiUrl}/datasets/${datasetUuid}/preview?limit=5`).subscribe({
      next: (preview) => {
        this.previewData.set(preview);
        this.previewLoading.set(false);
      },
      error: (error) => {
        console.error('Error loading preview:', error);
        this.previewLoading.set(false);
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
    this.analysisResult.set(null); // Cacher les anciens résultats pendant l'analyse

    this.startProgressSimulation();

    this.http.post(`${environment.apiUrl}/datasets/${datasetUuid}/analyze`, {}).subscribe({
      next: () => {
        this.updateProgress(50);
        this.pollAnalysis(datasetUuid);
      },
      error: (error) => {
        console.error('Error starting analysis:', error);
        this.stopProgress(false);
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
            // Analyse terminée avec des données
            this.stopProgress(true);
            this.analysisResult.set(result);
            this.updateKpiCardsFromAnalysis(result);
            this.analyzingDatasetId.set(null);
          } else {
            // Analyse pas encore prête (résultats null)
            attempts++;
            const progress = Math.min(90, 50 + (attempts * 2));
            this.updateProgress(progress);

            if (attempts >= maxAttempts) {
              this.stopProgress(false);
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
          this.updateProgress(progress);

          if (attempts >= maxAttempts) {
            this.stopProgress(false);
            this.analyzingDatasetId.set(null);
            this.analysisError.set('Délai d\'attente dépassé pour l\'analyse');
          } else if (error.status === 404 || error.status === 204) {
            setTimeout(checkAnalysis, 2000);
          } else {
            this.stopProgress(false);
            this.analyzingDatasetId.set(null);
            this.analysisError.set('Erreur lors du chargement de l\'analyse');
          }
        }
      });
    };

    setTimeout(checkAnalysis, 1000);
  }

  // ==================== TRAINING METHODS ====================

  startTraining(): void {
    if (!this.selectedDataset() || !this.modelName) return;

    this.training = true;
    this.http.post(`${environment.apiUrl}/models/train`, {
      modelName: this.modelName,
      datasetUuid: this.selectedDataset()!.datasetUuid
    }).subscribe({
      next: () => {
        this.training = false;
        this.trainingSuccess = true;
      },
      error: (err) => {
        this.training = false;
        alert("L'entraînement a échoué. Vérifiez les logs backend.");
      }
    });
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
    this.trainingSuccess = false;
    this.analysisError.set(null);
    this.analyzingDatasetId.set(null);
    this.modelName = '';
    this.clearProgressInterval();

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