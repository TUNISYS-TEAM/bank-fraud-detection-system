import { Component, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../environments/environment';
import {
  Dataset,
  FeatureInfo,
  TrainingJobRequest,
  TrainingJobResponse,
  ModelConfig
} from '../../../../core/models/ml-models.models';

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

@Component({
  selector: 'app-ml-models',
  templateUrl: './ml-models.component.html',
  styleUrls: ['./ml-models.component.css']
})
export class MlModelsComponent implements OnInit, OnDestroy {

  // Datasets
  allTrainingDatasets: Dataset[] = [];
  processedDatasets: Dataset[] = [];
  selectedDatasetUuid: string = '';
  selectedDataset: Dataset | null = null;

  // Pagination
  currentPage = 0;
  pageSize = 10;
  totalElements = 0;
  totalPages = 0;

  // Loading states
  isLoadingDatasets = false;
  isLoadingFeatures = false;

  // Features
  features: FeatureInfo[] = [];
  expandedCategories: Set<string> = new Set(['behavioral', 'temporal', 'transaction', 'categorical']);

  // Model selection
  availableModels = [
    {
      type: 'RANDOM_FOREST',
      name: 'Random Forest',
      description: 'Robuste et interprétable',
      icon: 'git-merge',
      color: 'blue',
      recommended: true,
      status: 'active'
    },
    {
      type: 'XGBOOST',
      name: 'XGBoost',
      description: 'Performances élevées',
      icon: 'trending-up',
      color: 'orange',
      recommended: false,
      status: 'soon'
    },
    {
      type: 'LIGHTGBM',
      name: 'LightGBM',
      description: 'Rapide et efficace',
      icon: 'zap',
      color: 'blue',
      recommended: false,
      status: 'soon'
    }
  ];
  selectedModelType: string = 'RANDOM_FOREST';

  // Random Forest configuration
  rfConfig: ModelConfig = {
    modelName: 'Random Forest',
    modelType: 'RANDOM_FOREST',
    hyperparameters: {
      n_estimators: 100,
      max_depth: 10,
      min_samples_split: 20,
      min_samples_leaf: 10,
      max_features: 'sqrt',
      bootstrap: true,
      class_weight: 'balanced'
    },
    testSize: 0.2,
    randomSeed: 42
  };

  // Detection threshold for UI
  detectionThreshold: number = 0.75;

  // Training state
  isTraining: boolean = false;
  lastTrainingResult: TrainingJobResponse | null = null;
  trainingError: string | null = null;
  trainingProgress: number = 0;
  trainingElapsedTime: number = 0;
  private trainingStartTime: number = 0;
  private trainingProgressInterval: any = null;
  private pollingInterval: any = null;

  // Statistics
  totalSelectedFeatures = 0;

  // Search
  searchQuery: string = '';

  // History
  modelHistory: any[] = [];
  historyCurrentPage = 0;
  historyPageSize = 5;
  historyTotalElements = 0;
  historyTotalPages = 0;

  // Modals
  showDeleteModal = false;
  showUpdateModal = false;
  selectedModelForAction: any = null;

  constructor(
    private http: HttpClient,
    private router: Router
  ) { }

  ngOnInit(): void {
    this.loadAllTrainingDatasets();
    this.loadModelHistory();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  private stopPolling(): void {
    if (this.pollingInterval) {
      clearInterval(this.pollingInterval);
      this.pollingInterval = null;
    }
    this.stopTrainingProgress();
  }

  private startTrainingProgress(): void {
    this.stopTrainingProgress();
    this.trainingProgress = 0;
    this.trainingStartTime = Date.now();
    this.trainingElapsedTime = 0;

    this.trainingProgressInterval = setInterval(() => {
      this.trainingElapsedTime = Date.now() - this.trainingStartTime;
      
      // Simulate progress: faster at first, then slows down
      if (this.trainingProgress < 90) {
        this.trainingProgress += (90 - this.trainingProgress) * 0.05;
      }
    }, 1000);
  }

  private stopTrainingProgress(): void {
    if (this.trainingProgressInterval) {
      clearInterval(this.trainingProgressInterval);
      this.trainingProgressInterval = null;
    }
  }

  get formattedTrainingTime(): string {
    const seconds = Math.floor(this.trainingElapsedTime / 1000);
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  }

  loadAllTrainingDatasets(): void {
    this.isLoadingDatasets = true;

    const url = `${environment.apiUrl}/datasets?type=TRANSACTIONS_TRAIN&page=${this.currentPage}&size=${this.pageSize}${this.searchQuery ? '&q=' + encodeURIComponent(this.searchQuery) : ''}`;

    this.http.get<PageResponse<Dataset>>(url).subscribe({
      next: (response) => {
        this.allTrainingDatasets = response.content;
        this.processedDatasets = response.content.filter(d => d.status === 'PROCESSED');
        this.totalElements = response.totalElements;
        this.totalPages = response.totalPages;
        this.currentPage = response.number;
        this.isLoadingDatasets = false;
      },
      error: (error) => {
        console.error('Error loading datasets:', error);
        this.allTrainingDatasets = [];
        this.processedDatasets = [];
        this.isLoadingDatasets = false;
      }
    });
  }

  onSearch(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.searchQuery = input.value;
    this.currentPage = 0;
    this.loadAllTrainingDatasets();
  }

  onPageChange(page: number): void {
    this.currentPage = page;
    this.loadAllTrainingDatasets();
  }

  onDatasetSelect(): void {
    if (this.selectedDatasetUuid) {
      this.isLoadingFeatures = true;

      this.http.get<Dataset>(`${environment.apiUrl}/datasets/${this.selectedDatasetUuid}`).subscribe({
        next: (dataset) => {
          this.selectedDataset = dataset;
          this.loadDatasetFeatures();
        },
        error: (error) => {
          console.error('Error loading dataset:', error);
          this.isLoadingFeatures = false;
        }
      });
    } else {
      this.selectedDataset = null;
      this.features = [];
      this.totalSelectedFeatures = 0;
    }
  }

  loadDatasetFeatures(): void {
    this.http.get<any>(`${environment.apiUrl}/datasets/${this.selectedDatasetUuid}/analysis`).subscribe({
      next: (analysis) => {
        // Mettre à jour les statistiques du dataset sélectionné pour éviter le "0%"
        if (this.selectedDataset && analysis.statistics) {
          this.selectedDataset.statistics = analysis.statistics;
        }
        this.features = this.extractFeaturesFromAnalysis(analysis);
        this.updateSelectedCount();
        this.isLoadingFeatures = false;
      },
      error: (error) => {
        console.error('Error loading features:', error);
        this.features = this.getDefaultFeatures();
        this.updateSelectedCount();
        this.isLoadingFeatures = false;
      }
    });
  }

  extractFeaturesFromAnalysis(analysis: any): FeatureInfo[] {
    const features: FeatureInfo[] = [];

    if (analysis?.columns) {
      analysis.columns.forEach((col: any) => {
        if (col.name === analysis.suggestedTarget) {
          return;
        }

        let category: 'behavioral' | 'temporal' | 'transaction' | 'categorical' = 'transaction';

        const name = col.name.toLowerCase();
        if (name.includes('client') || name.includes('customer') || name.includes('behavior') || name.includes('risk')) {
          category = 'behavioral';
        } else if (name.includes('hour') || name.includes('time') || name.includes('day') || name.includes('date') || name.includes('month') || name.includes('year')) {
          category = 'temporal';
        } else if (col.type === 'CATEGORICAL' || name.includes('channel') || name.includes('category') || name.includes('type')) {
          category = 'categorical';
        }

        features.push({
          name: col.name,
          enabled: true,
          type: col.type,
          description: this.generateFeatureDescription(col.name, col.type),
          category: category,
          importance: col.stats?.importance || this.getRandomImportance()
        });
      });
    }

    return features;
  }

  generateFeatureDescription(name: string, type: string): string {
    const descriptions: Record<string, string> = {
      'tx_amount': 'Montant de la transaction',
      'channel': 'Canal de transaction',
      'category': 'Catégorie marchande',
      'hour_of_day': 'Heure de la journée',
      'day_of_week': 'Jour de la semaine',
      'is_weekend': 'Transaction weekend',
      'is_night': 'Transaction nocturne',
      'client_avg_tx_amount': 'Montant moyen client',
      'client_tx_frequency': 'Fréquence transactions',
      'client_risk_score': 'Score de risque client',
      'amount_deviation': 'Écart au montant moyen'
    };

    return descriptions[name] || name.replace(/_/g, ' ').toLowerCase();
  }

  getRandomImportance(): number {
    return Math.random() * 0.3 + 0.1;
  }

  getDefaultFeatures(): FeatureInfo[] {
    return [
      { name: 'client_avg_tx_amount', enabled: true, type: 'NUMERIC', description: 'Montant moyen client', category: 'behavioral', importance: 0.32 },
      { name: 'client_tx_frequency', enabled: true, type: 'NUMERIC', description: 'Fréquence transactions', category: 'behavioral', importance: 0.28 },
      { name: 'client_risk_score', enabled: true, type: 'NUMERIC', description: 'Score de risque', category: 'behavioral', importance: 0.35 },
      { name: 'client_unusual_region', enabled: true, type: 'BINARY', description: 'Région inhabituelle', category: 'behavioral', importance: 0.18 },
      { name: 'hour_of_day', enabled: true, type: 'NUMERIC', description: 'Heure de la journée', category: 'temporal', importance: 0.22 },
      { name: 'day_of_week', enabled: true, type: 'NUMERIC', description: 'Jour de la semaine', category: 'temporal', importance: 0.15 },
      { name: 'is_weekend', enabled: true, type: 'BINARY', description: 'Week-end', category: 'temporal', importance: 0.12 },
      { name: 'is_night', enabled: true, type: 'BINARY', description: 'Transaction nocturne', category: 'temporal', importance: 0.19 },
      { name: 'tx_amount', enabled: true, type: 'NUMERIC', description: 'Montant', category: 'transaction', importance: 0.42 },
      { name: 'amount_deviation', enabled: true, type: 'NUMERIC', description: 'Écart au montant moyen', category: 'transaction', importance: 0.38 },
      { name: 'channel', enabled: true, type: 'CATEGORICAL', description: 'Canal', category: 'categorical', importance: 0.25 },
      { name: 'category', enabled: true, type: 'CATEGORICAL', description: 'Catégorie', category: 'categorical', importance: 0.21 }
    ];
  }

  getFeaturesByCategory(category: string): FeatureInfo[] {
    return this.features.filter(f => f.category === category);
  }

  isCategoryAllSelected(category: string): boolean {
    const categoryFeatures = this.getFeaturesByCategory(category);
    return categoryFeatures.length > 0 && categoryFeatures.every(f => f.enabled);
  }

  toggleCategory(category: string): void {
    const categoryFeatures = this.getFeaturesByCategory(category);
    const allSelected = this.isCategoryAllSelected(category);
    categoryFeatures.forEach(f => f.enabled = !allSelected);
    this.updateSelectedCount();
  }

  toggleCategoryExpand(category: string): void {
    if (this.expandedCategories.has(category)) {
      this.expandedCategories.delete(category);
    } else {
      this.expandedCategories.add(category);
    }
  }

  onFeatureToggle(): void {
    this.updateSelectedCount();
  }

  updateSelectedCount(): void {
    this.totalSelectedFeatures = this.features.filter(f => f.enabled).length;
  }

  selectAllFeatures(): void {
    this.features.forEach(f => f.enabled = true);
    this.updateSelectedCount();
  }

  isAllSelectedOverall(): boolean {
    return this.features.length > 0 && this.features.every(f => f.enabled);
  }

  deselectAllFeatures(): void {
    this.features.forEach(f => f.enabled = false);
    this.updateSelectedCount();
  }

  isNoneSelected(): boolean {
    return this.features.length > 0 && this.features.every(f => !f.enabled);
  }

  getCategoryIcon(category: string): string {
    const icons: Record<string, string> = {
      'behavioral': 'users',
      'temporal': 'clock',
      'transaction': 'credit-card',
      'categorical': 'tag'
    };
    return icons[category] || 'folder';
  }

  getCategoryColor(category: string): string {
    const colors: Record<string, string> = {
      'behavioral': 'text-purple-600 bg-purple-50',
      'temporal': 'text-blue-600 bg-blue-50',
      'transaction': 'text-green-600 bg-green-50',
      'categorical': 'text-amber-600 bg-amber-50'
    };
    return colors[category] || 'text-gray-600 bg-gray-50';
  }

  getCategoryHeaderBg(category: string): string {
    const colors: Record<string, string> = {
      'behavioral': 'bg-purple-50/50',
      'temporal': 'bg-blue-50/50',
      'transaction': 'bg-emerald-50/50',
      'categorical': 'bg-amber-50/50'
    };
    return colors[category] || 'bg-gray-50/50';
  }

  testModel(): void {
    const testConfig = { ...this.rfConfig };
    testConfig.hyperparameters = { ...this.rfConfig.hyperparameters };
    testConfig.hyperparameters.n_estimators = 50;
    this.startTrainingWithConfig(testConfig);
  }

  startTraining(): void {
    this.startTrainingWithConfig(this.rfConfig);
  }

  private startTrainingWithConfig(config: ModelConfig): void {
    if (!this.selectedDatasetUuid) {
      alert('Veuillez sélectionner un dataset');
      return;
    }

    if (this.totalSelectedFeatures === 0) {
      alert('Veuillez sélectionner au moins une feature');
      return;
    }

    // Stop any existing polling
    this.stopPolling();

    const enabledFeatures = this.features
      .filter(f => f.enabled)
      .map(f => f.name);

    const request: TrainingJobRequest = {
      datasetUuid: this.selectedDatasetUuid,
      jobName: `RF_${new Date().toISOString().slice(0, 10)}_${config.hyperparameters.n_estimators}estimators`,
      hyperparameters: {
        ...config.hyperparameters,
        selected_features: enabledFeatures,
        detection_threshold: this.detectionThreshold
      },
      testSize: config.testSize,
      randomSeed: config.randomSeed,
      enableHyperparameterTuning: false
    };

    this.isTraining = true;
    this.trainingError = null;
    this.startTrainingProgress();

    this.http.post<TrainingJobResponse>(
      `${environment.apiUrl}/v1/fraud-detection/models/training/random-forest`,
      request
    ).subscribe({
      next: (response) => {
        this.lastTrainingResult = response;
        this.isTraining = false;

        if (response.status === 'PENDING' || response.status === 'TRAINING') {
          this.pollTrainingStatus(response.modelUuid);
        }
        this.loadModelHistory();
      },
      error: (error) => {
        console.error('Training error:', error);
        this.isTraining = false;
        this.trainingError = error.error?.message || 'Erreur lors de l\'entraînement';
      }
    });
  }

  pollTrainingStatus(modelUuid: string): void {
    this.pollingInterval = setInterval(() => {
      this.http.get<any>(`${environment.apiUrl}/v1/fraud-detection/models/training/${modelUuid}/metrics`).subscribe({
        next: (metrics) => {
          if (metrics.status === 'Deployed' || metrics.status === 'Validated' || metrics.status === 'Archived') {
            this.trainingProgress = 100;
            this.stopPolling();
            this.stopTrainingProgress();
            this.loadModelHistory();

            if (this.lastTrainingResult) {
              // Ensure we have all the metrics in the right format
              const f1Score = metrics.f1Score || metrics.f1_score || 0;
              const precision = metrics.precision || 0;
              const recall = metrics.recall || 0;
              const aucRoc = metrics.aucRoc || metrics.auc_roc || 0;
              const accuracy = metrics.accuracy || 0;

              this.lastTrainingResult = {
                ...this.lastTrainingResult,
                status: 'COMPLETED',
                modelUuid: metrics.modelUuid,
                // For template interpolation
                metrics: {
                  f1_score: f1Score,
                  precision: precision,
                  recall: recall,
                  auc_roc: aucRoc,
                  accuracy: accuracy
                },
                // For direct access if needed
                f1Score: f1Score,
                precision: precision,
                recall: recall,
                aucRoc: aucRoc,
                fraudRate: metrics.fraudRate || this.lastTrainingResult.fraudRate || 0,
                confusionMatrix: metrics.confusionMatrix ? {
                  true_negatives: metrics.confusionMatrix.trueNegatives || metrics.confusionMatrix.true_negatives || 0,
                  false_positives: metrics.confusionMatrix.falsePositives || metrics.confusionMatrix.false_positives || 0,
                  false_negatives: metrics.confusionMatrix.falseNegatives || metrics.confusionMatrix.false_negatives || 0,
                  true_positives: metrics.confusionMatrix.truePositives || metrics.confusionMatrix.true_positives || 0
                } : this.lastTrainingResult.confusionMatrix
              };
            }
          }
        },
        error: (error) => {
          console.error('Error polling metrics:', error);
        }
      });
    }, 5000);
  }

  viewModelDetails(modelUuid: string): void {
    this.router.navigate(['/dashboard/models', modelUuid]);
  }

  formatNumber(value: number): string {
    if (!value && value !== 0) return '0';
    return new Intl.NumberFormat('fr-FR').format(value);
  }

  getEnabledCount(category: string): number {
    return this.getFeaturesByCategory(category).filter(f => f.enabled).length;
  }

  trackByUuid(index: number, item: any): string {
    return item?.datasetUuid || index.toString();
  }

  trackByType(index: number, item: any): string {
    return item?.type || index.toString();
  }

  trackByName(index: number, item: any): string {
    return item?.name || index.toString();
  }

  getDisplayRange(): { start: number, end: number } {
    const start = this.currentPage * this.pageSize + 1;
    const end = Math.min((this.currentPage + 1) * this.pageSize, this.totalElements);
    return { start, end };
  }

  hasPreviousPage(): boolean {
    return this.currentPage > 0;
  }

  hasNextPage(): boolean {
    return this.currentPage < this.totalPages - 1;
  }

  loadModelHistory(): void {
    const url = `${environment.apiUrl}/v1/fraud-detection/models/training/history?page=${this.historyCurrentPage}&size=${this.historyPageSize}`;
    this.http.get<any>(url).subscribe({
      next: (response) => {
        this.modelHistory = response.content;
        this.historyTotalElements = response.totalElements;
        this.historyTotalPages = response.totalPages;
        this.historyCurrentPage = response.number;
      },
      error: (error) => {
        console.error('Error loading history:', error);
      }
    });
  }

  onHistoryPageChange(page: number): void {
    this.historyCurrentPage = page;
    this.loadModelHistory();
  }

  hasPreviousHistoryPage(): boolean {
    return this.historyCurrentPage > 0;
  }

  hasNextHistoryPage(): boolean {
    return this.historyCurrentPage < this.historyTotalPages - 1;
  }

  getHistoryDisplayRange(): { start: number, end: number } {
    const start = this.historyCurrentPage * this.historyPageSize + 1;
    const end = Math.min((this.historyCurrentPage + 1) * this.historyPageSize, this.historyTotalElements);
    return { start, end };
  }

  openDeleteModal(model: any): void {
    this.selectedModelForAction = model;
    this.showDeleteModal = true;
  }

  openUpdateModal(model: any): void {
    this.selectedModelForAction = model;
    this.showUpdateModal = true;
  }

  reactivateModel(model: any): void {
    // 1. Restore Hyperparameters
    if (model.hyperparameters) {
      this.rfConfig.hyperparameters = { ...model.hyperparameters };
      // Remove metadata but keep the actual params
      delete (this.rfConfig.hyperparameters as any)._trainingMetadata;
      delete (this.rfConfig.hyperparameters as any)._trainingResults;
      
      this.selectedModelType = model.modelType;
      
      // 2. Restore Dataset selection if available
      const metadata = model.hyperparameters._trainingMetadata;
      if (metadata && metadata.datasetUuid) {
        this.selectedDatasetUuid = metadata.datasetUuid;
        this.onDatasetSelect(); // Load features for this dataset
      }

      // 3. Restore Selected Features
      const selectedFeatures = model.hyperparameters.selected_features;
      if (selectedFeatures && Array.isArray(selectedFeatures)) {
        // We need to wait for features to be loaded from onDatasetSelect()
        setTimeout(() => {
          this.features.forEach(f => {
            f.enabled = selectedFeatures.includes(f.name);
          });
          this.updateSelectedCount();
        }, 800); 
      }

      // 4. Restore Risk Threshold
      if (model.hyperparameters.detection_threshold) {
        this.detectionThreshold = model.hyperparameters.detection_threshold;
      }
    }

    // 5. Restore Results Section (Green part)
    const results = model.hyperparameters?._trainingResults;
    if (results) {
      this.lastTrainingResult = {
        modelUuid: model.modelUuid,
        status: 'COMPLETED',
        metrics: results.metrics,
        confusionMatrix: results.confusionMatrix,
        fraudRate: results.fraudRate,
        completedAt: results.completedAt,
        durationSeconds: results.durationSeconds
      } as any;
    }

    // Scroll to top to see results
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

}