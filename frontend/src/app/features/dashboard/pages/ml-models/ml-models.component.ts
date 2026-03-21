import { Component, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../environments/environment';
import {
  Dataset,
  FeatureInfo,
  TrainingJobRequest,
  TrainingJobResponse,
  ModelConfig,
  PageResponse
} from '../../../../core/models/ml-models.models';

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

  // Features - only original features shown in UI
  features: FeatureInfo[] = [];
  // Store all client behavior features to be sent automatically
  private allClientBehaviorFeatures: string[] = [];
  expandedCategories: Set<string> = new Set(['transaction', 'categorical', 'temporal']);

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

  // Random Forest configuration – only user‑adjustable parameters
  rfConfig: ModelConfig = {
    modelName: 'Random Forest',
    modelType: 'RANDOM_FOREST',
    hyperparameters: {
      n_estimators: 100,
      max_depth: 10
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

  // Model Details Modal
  showModelDetailsModal = false;
  selectedModelForDetails: any = null;

  // Original features mapping
  private readonly originalFeatures = new Set([
    'tx_amount', 'channel', 'category', 'customer_postal_code',
    'merchant_postal_code', 'device_id', 'tx_time', 'ip_address', 'currency',
    'account_id', 'TX_Amount', 'TX_Time', 'Account_Id', 'Customer_Postal_Code',
    'Merchant_Postal_Code', 'Device_Id', 'ip_adress'
  ]);

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
    this.stopTrainingProgress();
  }

  private stopPolling(): void {
    if (this.pollingInterval) {
      clearInterval(this.pollingInterval);
      this.pollingInterval = null;
    }
  }

  private stopTrainingProgress(): void {
    if (this.trainingProgressInterval) {
      clearInterval(this.trainingProgressInterval);
      this.trainingProgressInterval = null;
    }
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

  get formattedTrainingTime(): string {
    const seconds = Math.floor(this.trainingElapsedTime / 1000);
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  }

  loadAllTrainingDatasets(): void {
    this.isLoadingDatasets = true;

    const url = `${environment.apiUrl}/datasets?type=TRANSACTIONS_TRAIN_ENRICHED&page=${this.currentPage}&size=${this.pageSize}${this.searchQuery ? '&q=' + encodeURIComponent(this.searchQuery) : ''}`;

    this.http.get<PageResponse<Dataset>>(url).subscribe({
      next: (response) => {
        this.allTrainingDatasets = response.content;
        this.processedDatasets = response.content.filter(d => d.status === 'PROCESSED' || d.status === 'CLEANED');
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
      this.allClientBehaviorFeatures = [];
      this.totalSelectedFeatures = 0;
    }
  }

  loadDatasetFeatures(): void {
    this.http.get<any>(`${environment.apiUrl}/datasets/${this.selectedDatasetUuid}/analysis`).subscribe({
      next: (analysis) => {
        // Mettre à jour les statistiques du dataset sélectionné
        if (this.selectedDataset && analysis.statistics) {
          this.selectedDataset.statistics = analysis.statistics;
        }

        // Extract features - separate original and client behavior
        const { originalFeatures, clientBehaviorFeatures } = this.extractFeaturesFromAnalysis(analysis);
        this.features = originalFeatures;
        this.allClientBehaviorFeatures = clientBehaviorFeatures;

        this.updateSelectedCount();
        this.isLoadingFeatures = false;
      },
      error: (error) => {
        console.error('Error loading features:', error);
        const { originalFeatures, clientBehaviorFeatures } = this.getDefaultFeatures();
        this.features = originalFeatures;
        this.allClientBehaviorFeatures = clientBehaviorFeatures;
        this.updateSelectedCount();
        this.isLoadingFeatures = false;
      }
    });
  }

  extractFeaturesFromAnalysis(analysis: any): { originalFeatures: FeatureInfo[], clientBehaviorFeatures: string[] } {
    const originalFeatures: FeatureInfo[] = [];
    const clientBehaviorFeatures: string[] = [];

    if (analysis?.columns) {
      analysis.columns.forEach((col: any) => {
        if (col.name === analysis.suggestedTarget) {
          return;
        }

        const name = col.name.toLowerCase();

        // Check if it's a client behavior feature
        if (name.startsWith('client_')) {
          clientBehaviorFeatures.push(col.name);
          return;
        }

        // Only include original features that are in our set
        if (this.originalFeatures.has(name) || this.originalFeatures.has(col.name)) {
          let category: 'transaction' | 'categorical' | 'temporal' = 'transaction';

          if (name.includes('hour') || name.includes('time') || name.includes('day') ||
            name.includes('date') || name.includes('month') || name.includes('year')) {
            category = 'temporal';
          } else if (col.type === 'CATEGORICAL' || name.includes('channel') ||
            name.includes('category') || name.includes('type')) {
            category = 'categorical';
          }

          originalFeatures.push({
            name: col.name,
            enabled: true,
            type: col.type,
            description: this.generateFeatureDescription(col.name, col.type),
            category: category
          });
        }
      });
    }

    return { originalFeatures, clientBehaviorFeatures };
  }

  generateFeatureDescription(name: string, type: string): string {
    const descriptions: Record<string, string> = {
      'tx_amount': 'Montant de la transaction',
      'TX_Amount': 'Montant de la transaction',
      'channel': 'Canal de transaction',
      'Channel': 'Canal de transaction',
      'category': 'Catégorie marchande',
      'Category': 'Catégorie marchande',
      'customer_postal_code': 'Code postal client',
      'Customer_Postal_Code': 'Code postal client',
      'merchant_postal_code': 'Code postal marchand',
      'Merchant_Postal_Code': 'Code postal marchand',
      'device_id': 'ID de l\'appareil',
      'Device_Id': 'ID de l\'appareil',
      'tx_time': 'Date et heure de la transaction',
      'TX_Time': 'Date et heure de la transaction',
      'ip_address': 'Adresse IP',
      'ip_adress': 'Adresse IP',
      'currency': 'Devise',
      'account_id': 'ID du compte',
      'Account_Id': 'ID du compte'
    };

    return descriptions[name] || name.replace(/_/g, ' ').toLowerCase();
  }

  getDefaultFeatures(): { originalFeatures: FeatureInfo[], clientBehaviorFeatures: string[] } {
    return {
      originalFeatures: [
        { name: 'TX_Amount', enabled: true, type: 'NUMERIC', description: 'Montant de la transaction', category: 'transaction' },
        { name: 'Channel', enabled: true, type: 'CATEGORICAL', description: 'Canal de transaction', category: 'categorical' },
        { name: 'category', enabled: true, type: 'CATEGORICAL', description: 'Catégorie marchande', category: 'categorical' },
        { name: 'Customer_Postal_Code', enabled: true, type: 'CATEGORICAL', description: 'Code postal client', category: 'categorical' },
        { name: 'Merchant_Postal_Code', enabled: true, type: 'CATEGORICAL', description: 'Code postal marchand', category: 'categorical' },
        { name: 'Device_Id', enabled: true, type: 'CATEGORICAL', description: 'ID de l\'appareil', category: 'categorical' },
        { name: 'TX_Time', enabled: true, type: 'DATETIME', description: 'Date et heure de la transaction', category: 'temporal' },
        { name: 'ip_adress', enabled: true, type: 'CATEGORICAL', description: 'Adresse IP', category: 'categorical' },
        { name: 'currency', enabled: true, type: 'CATEGORICAL', description: 'Devise', category: 'categorical' },
        { name: 'Account_Id', enabled: true, type: 'CATEGORICAL', description: 'ID du compte', category: 'categorical' }
      ],
      clientBehaviorFeatures: [
        'client_AVG_TX_Amount', 'client_AVG_TX_PerDay', 'client_amount_deviation',
        'client_abs_amount_deviation', 'client_amount_max_ratio', 'client_amount_z_score',
        'client_is_new_max', 'client_is_new_min', 'client_total_transactions',
        'client_time_since_last_tx', 'client_hours_since_last_tx', 'client_total_days_since_first',
        'client_usual_region', 'client_is_unusual_region', 'client_region_freq',
        'client_usual_channel', 'client_is_unusual_channel', 'client_channel_freq',
        'client_usual_category', 'client_is_unusual_category', 'client_category_freq',
        'client_risk_score'
      ]
    };
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
      'temporal': 'clock',
      'transaction': 'credit-card',
      'categorical': 'tag'
    };
    return icons[category] || 'folder';
  }

  getCategoryColor(category: string): string {
    const colors: Record<string, string> = {
      'temporal': 'text-blue-600 bg-blue-50',
      'transaction': 'text-green-600 bg-green-50',
      'categorical': 'text-amber-600 bg-amber-50'
    };
    return colors[category] || 'text-gray-600 bg-gray-50';
  }

  getCategoryHeaderBg(category: string): string {
    const colors: Record<string, string> = {
      'temporal': 'bg-blue-50/50',
      'transaction': 'bg-emerald-50/50',
      'categorical': 'bg-amber-50/50'
    };
    return colors[category] || 'bg-gray-50/50';
  }

  testModel(): void {
    const testConfig = { ...this.rfConfig };
    testConfig.hyperparameters = {
      ...this.rfConfig.hyperparameters,
      n_estimators: 50
    };
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

    // Stop any existing polling and progress
    this.stopPolling();
    this.stopTrainingProgress();
    this.trainingError = null;

    // Get selected original features from UI
    const selectedOriginalFeatures = this.features
      .filter(f => f.enabled)
      .map(f => f.name);

    // Combine: selected original features + ALL client behavior features
    const allFeaturesForTraining = [...selectedOriginalFeatures, ...this.allClientBehaviorFeatures];

    // Build the hyperparameters object
    const hyperparameters: any = {
      n_estimators: config.hyperparameters.n_estimators,
      max_depth: config.hyperparameters.max_depth,
      selected_original_features: selectedOriginalFeatures,
      all_features: allFeaturesForTraining,
      detection_threshold: this.detectionThreshold
    };

    const request: TrainingJobRequest = {
      datasetUuid: this.selectedDatasetUuid,
      jobName: `RF_${new Date().toISOString().slice(0, 10)}_${config.hyperparameters.n_estimators}estimators`,
      hyperparameters: hyperparameters,
      testSize: config.testSize,
      randomSeed: config.randomSeed,
      enableHyperparameterTuning: false
    };

    this.isTraining = true;
    this.startTrainingProgress();

    this.http.post<TrainingJobResponse>(
      `${environment.apiUrl}/v1/fraud-detection/models/training/random-forest`,
      request
    ).subscribe({
      next: (response) => {
        this.lastTrainingResult = response;
        this.isTraining = false;
        this.stopTrainingProgress();
        this.trainingProgress = 100;

        if (response.status === 'PENDING' || response.status === 'TRAINING') {
          this.pollTrainingStatus(response.modelUuid);
        }
        this.loadModelHistory();
      },
      error: (error) => {
        console.error('Training error:', error);
        this.isTraining = false;
        this.stopTrainingProgress();
        this.trainingProgress = 0;
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
              const f1Score = metrics.f1Score || metrics.f1_score || 0;
              const precision = metrics.precision || 0;
              const recall = metrics.recall || 0;
              const aucRoc = metrics.aucRoc || metrics.auc_roc || 0;
              const accuracy = metrics.accuracy || 0;

              // Get thresholds from metrics if available
              const thresholds = metrics.thresholds || {
                fbeta: this.detectionThreshold,
                fbeta_score: f1Score,
                cost: this.detectionThreshold,
                min_cost: 0,
                final: this.detectionThreshold
              };

              this.lastTrainingResult = {
                ...this.lastTrainingResult,
                status: 'COMPLETED',
                modelUuid: metrics.modelUuid,
                metrics: {
                  f1_score: f1Score,
                  precision: precision,
                  recall: recall,
                  auc_roc: aucRoc,
                  accuracy: accuracy
                },
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
          this.stopPolling();
        }
      });
    }, 5000);
  }

  viewModelDetails(modelUuid: string): void {
    // Try to find the model in history first
    const modelFromHistory = this.modelHistory.find(m => m.modelUuid === modelUuid);

    if (modelFromHistory) {
      // Transform the history data into the format expected by the modal
      const modelData = this.transformModelDataForModal(modelFromHistory);
      this.selectedModelForDetails = modelData;
      this.showModelDetailsModal = true;
    } else {
      // If not in history, fetch from API
      this.http.get<any>(`${environment.apiUrl}/v1/fraud-detection/models/training/${modelUuid}`).subscribe({
        next: (model) => {
          const modelData = this.transformModelDataForModal(model);
          this.selectedModelForDetails = modelData;
          this.showModelDetailsModal = true;
        },
        error: (error) => {
          console.error('Error loading model details:', error);
        }
      });
    }
  }

  // Helper method to transform model data for the modal
  private transformModelDataForModal(model: any): any {
    // If the model already has the complete structure we need, return it
    if (model.metrics && model.confusionMatrix && model.thresholds) {
      return model;
    }

    // Extract data from nested structures
    const hyperparameters = model.hyperparameters || {};
    const trainingResults = hyperparameters._trainingResults || {};
    const trainingMetadata = hyperparameters._trainingMetadata || {};

    // Get metrics from various possible locations
    const metrics = model.metrics || trainingResults.metrics || {};

    // Get confusion matrix
    const confusionMatrix = model.confusionMatrix || trainingResults.confusionMatrix || {
      true_negatives: 0,
      false_positives: 0,
      false_negatives: 0,
      true_positives: 0
    };

    // Get thresholds
    const thresholds = model.thresholds || trainingResults.thresholds || hyperparameters._thresholds || {
      fbeta: hyperparameters.detection_threshold || 0.5,
      fbeta_score: metrics.f1_score || model.f1Score || 0,
      cost: hyperparameters.detection_threshold || 0.5,
      min_cost: 0,
      final: hyperparameters.detection_threshold || 0.7
    };

    // Get selected features
    const selectedOriginalFeatures = hyperparameters.selected_original_features || [];

    // Get all features
    const allFeatures = hyperparameters.all_features || [];

    return {
      modelUuid: model.modelUuid,
      modelName: model.modelName || 'Random Forest',
      status: model.status || 'COMPLETED',
      startedAt: trainingMetadata.trainingDate || model.startedAt || null,
      completedAt: trainingResults.completedAt || model.completedAt || null,
      durationSeconds: trainingResults.durationSeconds || model.durationSeconds || 0,
      fraudRate: trainingResults.fraudRate || model.fraudRate || 0,
      testRows: trainingResults.testRows || model.testRows || 0,
      trainingRows: trainingResults.trainingRows || model.trainingRows || 0,

      // Metrics
      metrics: {
        f1_score: metrics.f1_score || model.f1Score || metrics.f1Score || 0,
        precision: metrics.precision || model.precision || 0,
        recall: metrics.recall || model.recall || 0,
        auc_roc: metrics.auc_roc || model.aucRoc || metrics.aucRoc || 0,
        accuracy: metrics.accuracy || 0
      },

      // Confusion Matrix
      confusionMatrix: {
        true_negatives: confusionMatrix.true_negatives || confusionMatrix.trueNegatives || 0,
        false_positives: confusionMatrix.false_positives || confusionMatrix.falsePositives || 0,
        false_negatives: confusionMatrix.false_negatives || confusionMatrix.falseNegatives || 0,
        true_positives: confusionMatrix.true_positives || confusionMatrix.truePositives || 0
      },

      // Thresholds
      thresholds: {
        fbeta: thresholds.fbeta || 0.5,
        fbeta_score: thresholds.fbeta_score || metrics.f1_score || 0,
        cost: thresholds.cost || 0.5,
        min_cost: thresholds.min_cost || 0,
        final: thresholds.final || hyperparameters.detection_threshold || 0.7
      },

      // Features
      selected_original_features: selectedOriginalFeatures,
      hyperparameters_used: {
        all_features: allFeatures,
        n_estimators: hyperparameters.n_estimators || 100,
        max_depth: hyperparameters.max_depth || 10
      }
    };
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
    const url = `${environment.apiUrl}/v1/fraud-detection/models/training/my-models?page=${this.historyCurrentPage}&size=${this.historyPageSize}`;

    this.http.get<any>(url).subscribe({
      next: (response) => {
        // Transform each model to extract dates from nested structures
        this.modelHistory = response.content.map((model: any) => {
          const hyperparameters = model.hyperparameters || {};
          const trainingResults = hyperparameters._trainingResults || {};
          const trainingMetadata = hyperparameters._trainingMetadata || {};
          
          return {
            ...model,
            startedAt: trainingMetadata.trainingDate || model.startedAt,
            completedAt: trainingResults.completedAt || model.completedAt,
            durationSeconds: trainingResults.durationSeconds || model.durationSeconds,
            trainingRows: trainingResults.trainingRows || model.trainingRows,
            testRows: trainingResults.testRows || model.testRows,
            fraudRate: trainingResults.fraudRate || model.fraudRate
          };
        });
        
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
    if (model.hyperparameters) {
      const restoredParams: any = {};
      if (model.hyperparameters.n_estimators !== undefined) {
        restoredParams.n_estimators = model.hyperparameters.n_estimators;
      }
      if (model.hyperparameters.max_depth !== undefined) {
        restoredParams.max_depth = model.hyperparameters.max_depth;
      }

      this.rfConfig.hyperparameters = restoredParams;
      this.selectedModelType = model.modelType;

      const metadata = model.hyperparameters._trainingMetadata;
      if (metadata && metadata.datasetUuid) {
        this.selectedDatasetUuid = metadata.datasetUuid;
        this.onDatasetSelect();
      }

      const selectedFeatures = model.hyperparameters.selected_original_features;
      if (selectedFeatures && Array.isArray(selectedFeatures)) {
        setTimeout(() => {
          this.features.forEach(f => {
            f.enabled = selectedFeatures.includes(f.name);
          });
          this.updateSelectedCount();
        }, 800);
      }

      if (model.hyperparameters.detection_threshold !== undefined) {
        this.detectionThreshold = model.hyperparameters.detection_threshold;
      }
    }

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

    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  requestValidation(model: any): void {
    this.router.navigate(['/dashboard/messages'], {
      queryParams: {
        tab: 'validation',
        modelName: model.modelName,
        modelUuid: model.modelUuid,
        version: model.version,
        auc: model.hyperparameters?._trainingResults?.metrics?.auc_roc || 0,
        precision: model.precision || 0,
        recall: model.recall || 0,
        f1Score: model.f1Score || model.f1_score || 0,
        modelType: model.modelType,
        createdAt: model.createdAt || new Date().toISOString()
      }
    });
  }

  formatDate(date: string | null): string {
    if (!date) return 'N/A';
    try {
      return new Date(date).toLocaleString('fr-FR', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      });
    } catch (e) {
      return 'N/A';
    }
  }
}