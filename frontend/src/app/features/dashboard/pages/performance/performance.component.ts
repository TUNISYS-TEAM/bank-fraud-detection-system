import { Component, OnInit, OnDestroy, signal, computed, inject, HostListener, effect } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../environments/environment';
import * as echarts from 'echarts';
import { AuthService } from '../../../../core/services/auth.service';

interface ModelMetrics {
  modelUuid: string;
  modelName: string;
  modelType: string;
  version: string;
  status: string;
  validatedBy?: {
    id: number;
    email: string;
    firstName: string;
    lastName: string;
  };
  metrics: {
    f1_score: number;
    precision: number;
    recall: number;
    auc_roc: number;
    accuracy: number;
  };
  confusionMatrix?: {
    true_negatives: number;
    false_positives: number;
    false_negatives: number;
    true_positives: number;
  };
  thresholds?: {
    final: number;
    fbeta?: number;
    cost?: number;
    min_cost?: number;
  };
  trainingInfo: {
    trainingRows: number;
    testRows: number;
    fraudRate: number;
    durationSeconds: number;
    startedAt: string;
    completedAt: string;
  };
  hyperparameters: {
    n_estimators: number;
    max_depth: number;
    selected_features?: string[];
  };
}

interface CardData {
  label: string;
  value: string;
  icon: string;
  description: string;
  color: string;
  trend: string;
  trendUp: boolean;
}

@Component({
  selector: 'app-performance',
  templateUrl: './performance.component.html',
  styleUrls: ['./performance.component.css']
})
export class PerformanceComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private authService = inject(AuthService);

  // Data signals
  private rawModels = signal<ModelMetrics[]>([]);
  selectedModels = signal<ModelMetrics[]>([]);
  isLoading = signal(true);

  // Pagination signals
  currentPage = signal(0);
  readonly pageSize = signal(10);
  totalElements = signal(0);
  totalPages = signal(0);

  // Filter signals
  searchQuery = signal('');
  statusFilter = signal<'all' | 'Draft' | 'Validated' | 'Rejected' | 'Deployed'>(
    this.authService.getCurrentUser()?.role === 'MANAGER' ? 'all' : 'all'
  );
  showStatusFilter = signal(false);

  // Accordion signal
  private isAccordionOpenSignal = signal(true);

  // Role-based signals
  userRole = computed(() => this.authService.getCurrentUser()?.role || 'ANALYST');
  isManager = computed(() => this.userRole() === 'MANAGER');
  isAnalyst = computed(() => this.userRole() === 'ANALYST');
  currentUserEmail = computed(() => this.authService.getCurrentUser()?.email || '');

  // Chart instances
  private radarChart: any = null;
  private barChart: any = null;
  private rocCurvesChart: any = null;
  private thresholdChart: any = null;
  private confusionHeatmapChart: any = null;
  private driftChart: any = null;

  // Deployment signals
  isDeploying = signal(false);
  isUndeploying = signal(false);
  activeDeployedModel = signal<any>(null);

  // Model Drift tracking
  modelDriftData = signal<any>(null);
  isLoadingDrift = signal(false);
  driftThreshold = 15;
  driftAlert = signal<{ show: boolean; message: string; driftPercentage: number; level: 'critical' | 'warning' | 'info' | 'success' }>({
    show: false,
    message: '',
    driftPercentage: 0,
    level: 'success'
  });
  deploymentHistory: any[] = [];
  validatedModels: any[] = [];

  // Computed signals
  filteredModels = computed(() => {
    let models = this.rawModels();
    const search = this.searchQuery().toLowerCase().trim();
    const status = this.statusFilter();

    // For managers: show deployed models (any) AND validated models they approved
    if (this.isManager()) {
      models = models.filter(model =>
        // Deployed models are always visible (anyone can deploy)
        model.status === 'Deployed' ||
        // Validated models only visible if approved by current manager
        (model.status === 'Validated' && model.validatedBy?.email === this.currentUserEmail())
      );
    }

    // Apply status filter
    if (status !== 'all') {
      models = models.filter(model => model.status.toLowerCase() === status.toLowerCase());
    }

    // Apply search filter
    if (search) {
      models = models.filter(model =>
        model.modelName.toLowerCase().includes(search) ||
        model.modelUuid.toLowerCase().includes(search) ||
        model.version.toLowerCase().includes(search) ||
        model.modelType.toLowerCase().includes(search)
      );
    }

    return models;
  });

  modelCounts = computed(() => {
    let models = this.rawModels();

    if (this.isManager()) {
      models = models.filter(model =>
        model.status === 'Deployed' ||
        (model.status === 'Validated' && model.validatedBy?.email === this.currentUserEmail())
      );
    }

    return {
      total: models.length,
      draft: models.filter(m => m.status === 'Draft').length,
      validated: models.filter(m => m.status === 'Validated').length,
      deployed: models.filter(m => m.status === 'Deployed').length,
      rejected: models.filter(m => m.status === 'Rejected').length
    };
  });

  // Combined comparison source: selected models that match current filters
  filteredSelectedModels = computed(() => {
    const selected = this.selectedModels();
    const search = this.searchQuery().toLowerCase().trim();
    const status = this.statusFilter();

    return selected.filter(model => {
      // For managers, only show deployed or their validated models in comparison
      if (this.isManager()) {
        const isValidForManager = model.status === 'Deployed' ||
          (model.status === 'Validated' && model.validatedBy?.email === this.currentUserEmail());
        if (!isValidForManager) return false;
      }

      const matchStatus = status === 'all' || model.status.toLowerCase() === status.toLowerCase();
      const matchSearch = !search || (
        model.modelName.toLowerCase().includes(search) ||
        model.modelUuid.toLowerCase().includes(search) ||
        model.version.toLowerCase().includes(search) ||
        model.modelType.toLowerCase().includes(search)
      );
      return matchStatus && matchSearch;
    });
  });

  comparisonStats = computed(() => {
    const filteredSelected = this.filteredSelectedModels();
    if (filteredSelected.length === 0) return null;

    const bestByMetric = {
      f1_score: this.getBestModel(filteredSelected, 'f1_score'),
      precision: this.getBestModel(filteredSelected, 'precision'),
      recall: this.getBestModel(filteredSelected, 'recall'),
      auc_roc: this.getBestModel(filteredSelected, 'auc_roc')
    };

    const averages = {
      f1_score: filteredSelected.reduce((sum, m) => sum + m.metrics.f1_score, 0) / filteredSelected.length,
      precision: filteredSelected.reduce((sum, m) => sum + m.metrics.precision, 0) / filteredSelected.length,
      recall: filteredSelected.reduce((sum, m) => sum + m.metrics.recall, 0) / filteredSelected.length,
      auc_roc: filteredSelected.reduce((sum, m) => sum + m.metrics.auc_roc, 0) / filteredSelected.length
    };

    return { bestByMetric, averages };
  });

  bestF1Card = computed((): CardData | null => {
    const stats = this.comparisonStats();
    if (!stats?.bestByMetric.f1_score) return null;
    const model = stats.bestByMetric.f1_score;
    return {
      label: 'Meilleur F1-Score',
      value: this.formatPercent(model.metrics.f1_score),
      icon: 'award',
      description: model.modelName,
      color: '#10b981',
      trend: `${(model.metrics.f1_score * 100).toFixed(1)}%`,
      trendUp: true
    };
  });

  bestPrecisionCard = computed((): CardData | null => {
    const stats = this.comparisonStats();
    if (!stats?.bestByMetric.precision) return null;
    const model = stats.bestByMetric.precision;
    return {
      label: 'Meilleure Précision',
      value: this.formatPercent(model.metrics.precision),
      icon: 'check-circle',
      description: model.modelName,
      color: '#3b82f6',
      trend: `${(model.metrics.precision * 100).toFixed(1)}%`,
      trendUp: true
    };
  });

  bestRecallCard = computed((): CardData | null => {
    const stats = this.comparisonStats();
    if (!stats?.bestByMetric.recall) return null;
    const model = stats.bestByMetric.recall;
    return {
      label: 'Meilleur Rappel',
      value: this.formatPercent(model.metrics.recall),
      icon: 'activity',
      description: model.modelName,
      color: '#8b5cf6',
      trend: `${(model.metrics.recall * 100).toFixed(1)}%`,
      trendUp: true
    };
  });

  bestAucRocCard = computed((): CardData | null => {
    const stats = this.comparisonStats();
    if (!stats?.bestByMetric.auc_roc) return null;
    const model = stats.bestByMetric.auc_roc;
    return {
      label: 'Meilleur AUC-ROC',
      value: this.formatPercent(model.metrics.auc_roc),
      icon: 'trending-up',
      description: model.modelName,
      color: '#f59e0b',
      trend: `${(model.metrics.auc_roc * 100).toFixed(1)}%`,
      trendUp: true
    };
  });

  hasSelectedModels = computed(() => this.selectedModels().length > 0);
  hasComparisonStats = computed(() => this.comparisonStats() !== null);
  hasFilteredModels = computed(() => this.filteredModels().length > 0);
  hasMultiplePages = computed(() => this.totalPages() > 1);

  statusFilterLabel = computed(() => {
    const filter = this.statusFilter();
    switch (filter) {
      case 'all': return 'Tous';
      case 'Draft': return 'Draft';
      case 'Validated': return 'Validés';
      case 'Deployed': return 'Déployés';
      case 'Rejected': return 'Rejetés';
      default: return 'Tous';
    }
  });

  constructor() {
    // FIX: Use effect with manual initialization instead of writing to signals
    effect(() => {
      const selected = this.filteredSelectedModels();
      // Use setTimeout to avoid signal writes during effect execution
      setTimeout(() => {
        if (selected.length > 0) {
          this.initCharts();
        } else {
          this.disposeCharts();
        }
      }, 0);
    });

    // FIX: For drift tracking - use effect without writing to signals directly
    effect(() => {
      const deployedModel = this.activeDeployedModel();
      if (this.isManager() && deployedModel) {
        // Use setTimeout to avoid signal writes during effect
        setTimeout(() => {
          this.loadModelDriftData();
        }, 0);
      }
    });
  }

  ngOnInit(): void {
    this.loadModels();
    this.loadActiveDeployedModel();
    if (this.isManager()) {
      this.loadDeploymentHistory();
      this.loadValidatedModels();
    }
  }

  ngOnDestroy(): void {
    this.disposeCharts();
  }

  toggleAccordion(): void {
    this.isAccordionOpenSignal.update(v => !v);
  }

  isAccordionOpen(): boolean {
    return this.isAccordionOpenSignal();
  }

  loadModels(callback?: () => void): void {
    this.isLoading.set(true);

    let url = `${environment.apiUrl}/v1/fraud-detection/models/training/my-models?page=${this.currentPage()}&size=${this.pageSize()}`;

    const search = this.searchQuery();
    if (search) url += `&search=${encodeURIComponent(search)}`;

    // Don't filter by status in API for managers - we'll filter in component
    if (!this.isManager() && this.statusFilter() !== 'all') {
      url += `&status=${this.statusFilter()}`;
    }

    this.http.get<any>(url).subscribe({
      next: (response) => {
        let transformedModels: ModelMetrics[] = response.content.map((model: any) => this.transformModel(model));

        // For managers: show deployed models (any) AND validated models they approved
        if (this.isManager()) {
          transformedModels = transformedModels.filter((model: ModelMetrics) =>
            model.status === 'Deployed' ||
            (model.status === 'Validated' && model.validatedBy?.email === this.currentUserEmail())
          );
        }

        this.rawModels.set(transformedModels);
        this.totalElements.set(transformedModels.length);
        this.totalPages.set(Math.ceil(transformedModels.length / this.pageSize()));
        this.isLoading.set(false);

        if (transformedModels.length > 0 && this.selectedModels().length === 0) {
          const toSelect = transformedModels.slice(0, Math.min(3, transformedModels.length));
          this.selectedModels.set(toSelect);
        }

        if (callback) callback();
      },
      error: (error) => {
        console.error('Error loading models:', error);
        this.isLoading.set(false);
      }
    });
  }

  loadActiveDeployedModel(): void {
    this.http.get(`${environment.apiUrl}/v1/fraud-detection/deployment/active`)
      .subscribe({
        next: (response: any) => {
          this.activeDeployedModel.set(response);
        },
        error: () => { 
          this.activeDeployedModel.set(null);
        }
      });
  }

  loadDeploymentHistory(): void {
    this.http.get(`${environment.apiUrl}/v1/fraud-detection/deployment/history`)
      .subscribe({
        next: (response: any) => {
          this.deploymentHistory = response.data || response || [];
        },
        error: () => { }
      });
  }

  loadValidatedModels(): void {
    this.http.get(`${environment.apiUrl}/v1/fraud-detection/deployment/validated-models`)
      .subscribe({
        next: (response: any) => {
          this.validatedModels = response.data || response || [];
        },
        error: () => { }
      });
  }

  isModelDeployed(modelUuid: string): boolean {
    return this.activeDeployedModel()?.modelUuid === modelUuid;
  }

  deployModel(model: any): void {
    if (!this.isManager()) {
      alert('Seuls les managers peuvent déployer des modèles');
      return;
    }

    if (!confirm(`Déployer le modèle "${model.modelName}" ?\n\nLe modèle actuellement déployé sera remplacé.`)) {
      return;
    }

    this.isDeploying.set(true);

    const request = {
      modelUuid: model.modelUuid,
      notes: `Déployé par ${this.currentUserEmail()} le ${new Date().toLocaleString()}`
    };

    this.http.post(`${environment.apiUrl}/v1/fraud-detection/deployment/deploy`, request)
      .subscribe({
        next: () => {
          this.loadActiveDeployedModel();
          this.isDeploying.set(false);
          this.loadModels(() => {
            setTimeout(() => {
              alert(`Modèle "${model.modelName}" déployé avec succès !`);
            }, 100);
          });
        },
        error: (error) => {
          alert(`Erreur lors du déploiement: ${error.error?.message || 'Unknown error'}`);
          this.isDeploying.set(false);
        }
      });
  }

  undeployModel(): void {
    if (!this.isManager()) {
      alert('Seuls les managers peuvent déployer/retirer des modèles');
      return;
    }

    const currentModel = this.activeDeployedModel();
    if (!currentModel) {
      alert('Aucun modèle n\'est actuellement déployé');
      return;
    }

    if (!confirm(`Retirer le modèle "${currentModel.modelName}" de la production ?\n\nLes transactions ne seront plus scorées par ce modèle.`)) {
      return;
    }

    this.isUndeploying.set(true);

    this.http.post(`${environment.apiUrl}/v1/fraud-detection/deployment/undeploy?modelUuid=${currentModel.modelUuid}`, {})
      .subscribe({
        next: () => {
          this.activeDeployedModel.set(null);
          this.loadActiveDeployedModel();
          this.isUndeploying.set(false);
          this.loadModels(() => {
            setTimeout(() => {
              alert(`Modèle "${currentModel.modelName}" retiré avec succès !`);
            }, 100);
          });
        },
        error: (error) => {
          alert(`Erreur lors du retrait: ${error.error?.message || 'Unknown error'}`);
          this.isUndeploying.set(false);
        }
      });
  }

  private transformModel(model: any): ModelMetrics {
    const hyperparameters = model.hyperparameters || {};
    const trainingResults = hyperparameters._trainingResults || {};
    const metrics = model.metrics || trainingResults.metrics || {};

    let validatedBy = undefined;
    if (model.validatedBy) {
      validatedBy = {
        id: model.validatedBy.id,
        email: model.validatedBy.email,
        firstName: model.validatedBy.firstName,
        lastName: model.validatedBy.lastName
      };
    }

    return {
      modelUuid: model.modelUuid,
      modelName: model.modelName || 'Random Forest',
      modelType: model.modelType || 'RANDOM_FOREST',
      version: model.version || '1.0',
      status: model.status || 'COMPLETED',
      validatedBy: validatedBy,
      metrics: {
        f1_score: metrics.f1_score || model.f1Score || metrics.f1Score || 0,
        precision: metrics.precision || model.precision || 0,
        recall: metrics.recall || model.recall || 0,
        auc_roc: metrics.auc_roc || model.aucRoc || metrics.aucRoc || 0,
        accuracy: metrics.accuracy || 0
      },
      confusionMatrix: model.confusionMatrix || trainingResults.confusionMatrix,
      thresholds: model.thresholds || trainingResults.thresholds,
      trainingInfo: {
        trainingRows: trainingResults.trainingRows || model.trainingRows || 0,
        testRows: trainingResults.testRows || model.testRows || 0,
        fraudRate: trainingResults.fraudRate || model.fraudRate || 0,
        durationSeconds: trainingResults.durationSeconds || model.durationSeconds || 0,
        startedAt: hyperparameters._trainingMetadata?.trainingDate || model.startedAt,
        completedAt: trainingResults.completedAt || model.completedAt
      },
      hyperparameters: {
        n_estimators: hyperparameters.n_estimators || 100,
        max_depth: hyperparameters.max_depth || 10,
        selected_features: hyperparameters.selected_original_features || []
      }
    };
  }

  toggleModelSelection(model: ModelMetrics): void {
    // For managers, prevent selecting models they don't have permission to see
    if (this.isManager()) {
      const isValidForManager = model.status === 'Deployed' ||
        (model.status === 'Validated' && model.validatedBy?.email === this.currentUserEmail());
      if (!isValidForManager) {
        alert('Vous ne pouvez pas sélectionner ce modèle');
        return;
      }
    }

    this.selectedModels.update(current => {
      const isSelected = current.some(m => m.modelUuid === model.modelUuid);

      if (isSelected) {
        return current.filter(m => m.modelUuid !== model.modelUuid);
      } else {
        if (current.length >= 5) {
          alert('Vous pouvez comparer jusqu\'à 5 modèles maximum');
          return current;
        }
        return [...current, model];
      }
    });
  }

  isModelSelected(modelUuid: string): boolean {
    return this.selectedModels().some(m => m.modelUuid === modelUuid);
  }

  clearSelection(): void {
    this.selectedModels.set([]);
  }

  private getBestModel(models: ModelMetrics[], metric: keyof ModelMetrics['metrics']): ModelMetrics | null {
    if (models.length === 0) return null;
    return models.reduce((best, current) =>
      current.metrics[metric] > best.metrics[metric] ? current : best
    );
  }

  toggleStatusFilter(): void {
    this.showStatusFilter.update(v => !v);
  }

  onSearchChange(value: string) {
    this.searchQuery.set(value);
    this.currentPage.set(0);
    this.loadModels();
  }

  setStatusFilter(status: 'all' | 'Draft' | 'Validated' | 'Rejected' | 'Deployed'): void {
    this.statusFilter.set(status);
    this.showStatusFilter.set(false);
    this.currentPage.set(0);
    this.loadModels();
  }

  getStatusBadgeClass(status: string): string {
    if (!status) return 'bg-gray-100 text-gray-700';
    const s = status.toLowerCase();
    switch (s) {
      case 'draft':
        return 'bg-gray-100 text-gray-700';
      case 'validated':
        return 'bg-green-100 text-green-700';
      case 'rejected':
        return 'bg-red-100 text-red-700';
      case 'deployed':
        return 'bg-blue-100 text-blue-700';
      default:
        return 'bg-gray-100 text-gray-700';
    }
  }

  @HostListener('document:click', ['$event'])
  onClickOutside(event: MouseEvent): void {
    const target = event.target as HTMLElement;
    if (!target.closest('.relative')) {
      this.showStatusFilter.set(false);
    }
  }

  private disposeCharts(): void {
    const charts = [
      this.radarChart,
      this.barChart,
      this.rocCurvesChart,
      this.thresholdChart,
      this.confusionHeatmapChart,
      this.driftChart
    ];
    charts.forEach(chart => {
      if (chart) {
        chart.dispose();
      }
    });

    this.radarChart = null;
    this.barChart = null;
    this.rocCurvesChart = null;
    this.thresholdChart = null;
    this.confusionHeatmapChart = null;
    this.driftChart = null;
  }

  private initCharts(): void {
    setTimeout(() => {
      this.initRadarChart();
      this.initBarChart();
      this.initROCCurvesChart();
      this.initThresholdChart();
      this.initConfusionHeatmapChart();
    }, 100);
  }

  private initRadarChart(): void {
    const chartDom = document.getElementById('radarComparisonChart');
    const selected = this.filteredSelectedModels();
    if (!chartDom || selected.length === 0) return;

    if (this.radarChart) this.radarChart.dispose();
    this.radarChart = echarts.init(chartDom);

    const indicators = [
      { name: 'F1-Score', max: 1 },
      { name: 'Précision', max: 1 },
      { name: 'Rappel', max: 1 },
      { name: 'AUC-ROC', max: 1 },
      { name: 'Accuracy', max: 1 }
    ];

    const seriesData = selected.map((model, index) => ({
      value: [
        model.metrics.f1_score,
        model.metrics.precision,
        model.metrics.recall,
        model.metrics.auc_roc,
        model.metrics.accuracy
      ],
      name: model.modelName,
      areaStyle: {
        color: `rgba(${this.getColorForIndex(index)}, 0.2)`
      },
      lineStyle: {
        color: `rgb(${this.getColorForIndex(index)})`,
        width: 2
      },
      itemStyle: {
        color: `rgb(${this.getColorForIndex(index)})`
      }
    }));

    this.radarChart.setOption({
      title: {
        text: 'Comparaison des Métriques',
        left: 'center',
        top: 10,
        textStyle: { fontSize: 14, fontWeight: 'bold' }
      },
      tooltip: { trigger: 'item' },
      legend: {
        data: selected.map(m => m.modelName),
        orient: 'vertical',
        left: 'left',
        top: 'center'
      },
      radar: {
        indicator: indicators,
        shape: 'circle',
        center: ['50%', '50%'],
        radius: '60%'
      },
      series: [{
        type: 'radar',
        data: seriesData
      }]
    });

    window.addEventListener('resize', () => this.radarChart?.resize());
  }

  private initBarChart(): void {
    const chartDom = document.getElementById('barComparisonChart');
    const selected = this.filteredSelectedModels();
    if (!chartDom || selected.length === 0) return;

    if (this.barChart) this.barChart.dispose();
    this.barChart = echarts.init(chartDom);

    const metrics = ['F1-Score', 'Précision', 'Rappel', 'AUC-ROC', 'Accuracy'];
    const colors = selected.map((_, i) => `rgb(${this.getColorForIndex(i)})`);

    const seriesData = selected.map((model, index) => ({
      name: model.modelName,
      type: 'bar',
      data: [
        model.metrics.f1_score,
        model.metrics.precision,
        model.metrics.recall,
        model.metrics.auc_roc,
        model.metrics.accuracy
      ],
      itemStyle: {
        color: colors[index],
        borderRadius: [4, 4, 0, 0]
      },
      label: {
        show: true,
        position: 'top',
        formatter: (params: any) => (params.value * 100).toFixed(1) + '%',
        fontSize: 10
      }
    }));

    this.barChart.setOption({
      title: {
        text: 'Comparaison des Performances',
        left: 'center',
        top: 10,
        textStyle: { fontSize: 14, fontWeight: 'bold' }
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: any) => {
          let result = `<strong>${params[0].axisValue}</strong><br/>`;
          params.forEach((p: any) => {
            result += `${p.marker} ${p.seriesName}: ${(p.value * 100).toFixed(1)}%<br/>`;
          });
          return result;
        }
      },
      legend: {
        data: this.selectedModels().map(m => m.modelName),
        top: 40
      },
      grid: {
        left: '8%',
        right: '5%',
        bottom: '10%',
        top: '20%'
      },
      xAxis: {
        type: 'category',
        data: metrics,
        axisLabel: { fontSize: 11, fontWeight: 'bold' }
      },
      yAxis: {
        type: 'value',
        name: 'Score',
        min: 0,
        max: 1,
        axisLabel: {
          formatter: (value: number) => (value * 100).toFixed(0) + '%'
        }
      },
      series: seriesData
    });

    window.addEventListener('resize', () => this.barChart?.resize());
  }

  private initROCCurvesChart(): void {
    const chartDom = document.getElementById('rocCurvesChart');
    const selected = this.filteredSelectedModels();
    if (!chartDom || selected.length === 0) return;

    if (this.rocCurvesChart) this.rocCurvesChart.dispose();
    this.rocCurvesChart = echarts.init(chartDom);

    const seriesData = selected.map((model, index) => {
      const auc = model.metrics.auc_roc;
      const points = 50;
      const fpr: number[] = [];
      const tpr: number[] = [];

      for (let i = 0; i <= points; i++) {
        const x = i / points;
        fpr.push(x);
        const y = Math.pow(x, (1 - auc) / Math.max(auc, 0.05));
        tpr.push(y);
      }

      return {
        name: `${model.modelName} (AUC: ${(auc * 100).toFixed(1)}%)`,
        type: 'line',
        data: fpr.map((x, i) => [x, tpr[i]]),
        smooth: true,
        symbol: 'none',
        lineStyle: {
          color: `rgb(${this.getColorForIndex(index)})`,
          width: 2.5
        }
      };
    });

    this.rocCurvesChart.setOption({
      title: {
        text: 'Courbes ROC Comparées',
        left: 'center',
        top: 10,
        textStyle: { fontSize: 14, fontWeight: 'bold' }
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        formatter: (params: any) => {
          if (params && params[0]) {
            return `FPR: ${params[0].data[0].toFixed(3)}<br/>TPR: ${params[0].data[1].toFixed(3)}`;
          }
          return '';
        }
      },
      legend: {
        data: seriesData.map(s => s.name),
        top: 40,
        left: 'center',
        orient: 'horizontal'
      },
      grid: {
        left: '10%',
        right: '8%',
        bottom: '10%',
        top: '20%'
      },
      xAxis: {
        type: 'value',
        name: 'Taux Faux Positifs (FPR)',
        min: 0,
        max: 1
      },
      yAxis: {
        type: 'value',
        name: 'Taux Vrais Positifs (TPR)',
        min: 0,
        max: 1
      },
      series: seriesData
    });

    window.addEventListener('resize', () => this.rocCurvesChart?.resize());
  }

  private initThresholdChart(): void {
    const chartDom = document.getElementById('thresholdComparisonChart');
    const selected = this.filteredSelectedModels();
    if (!chartDom || selected.length === 0) return;

    if (this.thresholdChart) this.thresholdChart.dispose();
    this.thresholdChart = echarts.init(chartDom);

    const thresholds = [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9];

    const seriesData = selected.map((model, index) => {
      const f1Scores = thresholds.map(t => {
        const baseF1 = model.metrics.f1_score;
        return Math.max(0.1, baseF1 * (1 - Math.abs(t - 0.5) * 0.5));
      });

      return {
        name: model.modelName,
        type: 'line',
        data: f1Scores,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: {
          color: `rgb(${this.getColorForIndex(index)})`,
          width: 2.5
        },
        itemStyle: {
          color: `rgb(${this.getColorForIndex(index)})`
        }
      };
    });

    this.thresholdChart.setOption({
      title: {
        text: 'Impact du Seuil sur F1-Score',
        left: 'center',
        top: 10,
        textStyle: { fontSize: 14, fontWeight: 'bold' }
      },
      tooltip: {
        trigger: 'axis',
        formatter: (params: any) => {
          let result = `<strong>Seuil: ${params[0].axisValue}</strong><br/>`;
          params.forEach((p: any) => {
            result += `${p.marker} ${p.seriesName}: ${(p.value * 100).toFixed(1)}%<br/>`;
          });
          return result;
        }
      },
      legend: {
        data: selected.map(m => m.modelName),
        top: 40
      },
      grid: {
        left: '10%',
        right: '8%',
        bottom: '10%',
        top: '20%'
      },
      xAxis: {
        type: 'category',
        name: 'Seuil de Détection',
        data: thresholds.map(t => (t * 100).toFixed(0) + '%'),
        axisLabel: { rotate: 45 }
      },
      yAxis: {
        type: 'value',
        name: 'F1-Score',
        min: 0,
        max: 1,
        axisLabel: {
          formatter: (value: number) => (value * 100).toFixed(0) + '%'
        }
      },
      series: seriesData
    });

    window.addEventListener('resize', () => this.thresholdChart?.resize());
  }

  private initConfusionHeatmapChart(): void {
    const chartDom = document.getElementById('confusionHeatmapChart');
    const selected = this.filteredSelectedModels();
    if (!chartDom || selected.length === 0) return;

    if (this.confusionHeatmapChart) this.confusionHeatmapChart.dispose();
    this.confusionHeatmapChart = echarts.init(chartDom);

    const metrics = ['Vrais Négatifs', 'Faux Positifs', 'Faux Négatifs', 'Vrais Positifs'];
    const data: any[] = [];

    selected.forEach((m, modelIndex) => {
      const cm = m.confusionMatrix;
      if (cm) {
        const total = cm.true_negatives + cm.false_positives + cm.false_negatives + cm.true_positives;
        const values = [cm.true_negatives, cm.false_positives, cm.false_negatives, cm.true_positives];
        values.forEach((v, metricIndex) => {
          const percent = total > 0 ? (v / total * 100) : 0;
          data.push([modelIndex, metricIndex, percent, v]);
        });
      }
    });

    if (data.length === 0) return;

    this.confusionHeatmapChart.setOption({
      tooltip: {
        position: 'top',
        formatter: (params: any) => {
          const val = params.data[3];
          const pct = params.data[2].toFixed(2);
          return `<div class="p-2 font-sans">
            <div class="text-xs font-black text-slate-400 uppercase tracking-widest mb-1">${selected[params.data[0]].modelName}</div>
            <div class="flex items-center justify-between gap-4">
              <span class="text-xs font-medium text-slate-600">${metrics[params.data[1]]}</span>
              <span class="text-sm font-black text-slate-900">${val.toLocaleString()}</span>
            </div>
            <div class="text-[10px] text-blue-500 font-bold mt-1">${pct}% du total test</div>
          </div>`;
        }
      },
      grid: {
        top: 20,
        bottom: 115,
        left: 120,
        right: 40
      },
      xAxis: {
        type: 'category',
        data: selected.map(m => m.modelName),
        splitArea: { show: true },
        axisLabel: { rotate: 25, fontSize: 10, fontWeight: 'bold' }
      },
      yAxis: {
        type: 'category',
        data: metrics,
        splitArea: { show: true },
        axisLabel: { fontWeight: 'bold', fontSize: 11 }
      },
      visualMap: {
        min: 0,
        max: 100,
        calculable: true,
        orient: 'horizontal',
        left: 'center',
        bottom: -20,
        dimension: 2,
        formatter: (value: number) => value.toFixed(0) + '%',
        inRange: {
          color: ['#f8fafc', '#dbeafe', '#3b82f6', '#1d4ed8']
        },
        outOfRange: {
          opacity: 0
        }
      },
      series: [{
        name: 'Confusion Matrix',
        type: 'heatmap',
        data: data,
        label: {
          show: true,
          formatter: (params: any) => {
            const rawVal = params.data[3];
            return rawVal > 1000 ? (rawVal / 1000).toFixed(1) + 'k' : rawVal;
          },
          fontSize: 12,
          fontWeight: 'black'
        },
        itemStyle: {
          borderWidth: 2,
          borderColor: '#ffffff'
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowColor: 'rgba(0, 0, 0, 0.2)'
          }
        }
      }]
    });

    window.addEventListener('resize', () => this.confusionHeatmapChart?.resize());
  }

  public getColorForIndex(index: number): string {
    const colors = [
      '59, 130, 246',   // Blue 500
      '16, 185, 129',   // Emerald 500
      '139, 92, 246',   // Violet 500
      '245, 158, 11',   // Amber 500
      '239, 68, 68',    // Red 500
      '236, 72, 153',   // Pink 500
      '14, 165, 233',   // Sky 500
      '168, 85, 247',   // Purple 500
      '249, 115, 22',   // Orange 500
      '20, 184, 166'    // Teal 500
    ];
    return colors[index % colors.length];
  }

  // ============================================
  // MODEL DRIFT TRACKING METHODS
  // ============================================

  loadModelDriftData(): void {
    if (!this.isManager() || !this.activeDeployedModel()) {
      return;
    }

    this.isLoadingDrift.set(true);
    
    this.http.get(`${environment.apiUrl}/v1/fraud-detection/deployment/drift/status`).subscribe({
      next: (response: any) => {
        if (response.status === 'NO_DRIFT_DATA' || response.status === 'NO_ACTIVE_MODEL') {
          this.generateFallbackDriftDataFromModel(this.activeDeployedModel());
          return;
        }

        const driftData = this.transformBackendDriftData(response);
        this.modelDriftData.set(driftData);
        this.updateDriftAlert(driftData);
        this.isLoadingDrift.set(false);
        setTimeout(() => this.initDriftChart(), 100);
      },
      error: (error) => {
        console.error('Error loading drift data:', error);
        this.generateFallbackDriftDataFromModel(this.activeDeployedModel());
      }
    });
  }

  triggerManualDriftCheck(): void {
    if (!this.isManager()) return;
    
    this.isLoadingDrift.set(true);
    this.http.post(`${environment.apiUrl}/v1/fraud-detection/deployment/drift/check`, {}).subscribe({
      next: () => {
        this.loadModelDriftData();
        // Show a brief success notification if you have a toast system, or just reload
      },
      error: (error) => {
        console.error('Error triggering drift check:', error);
        alert('Erreur lors du déclenchement de l\'analyse de dérive');
        this.isLoadingDrift.set(false);
      }
    });
  }

  private transformBackendDriftData(response: any): any {
    const history = response.drift_history || [];
    const driftValues = history.map((h: any) => h.overall_drift_percentage || 0);
    const periods = history.map((h: any) => {
      const date = new Date(h.checked_at);
      return date.toLocaleDateString('fr-FR', { month: 'short', day: 'numeric', hour: '2-digit' });
    });

    // Ensure we have at least 5 points for the chart if possible, or just what we have
    const lastPoint = history.length > 0 ? history[history.length - 1] : null;

    return {
      periods: periods,
      driftValues: driftValues,
      threshold: this.driftThreshold,
      currentDrift: lastPoint?.overall_drift_percentage || 0,
      isCritical: response.current_status === 'CRITICAL_DRIFT',
      recommendations: this.getRecommendationsBasedOnDrift(lastPoint?.overall_drift_percentage || 0, response.current_status),
      performanceMetrics: lastPoint?.current_metrics || {},
      dataPoints: history,
      alertLevel: this.getAlertLevel(lastPoint?.overall_drift_percentage || 0),
      modelName: response.model_name,
      deploymentDate: response.last_check
    };
  }

  // Remove obsolete manual calculation helpers

  private generateFallbackDriftDataFromModel(model: any): void {
    const modelMetrics = model.metrics || {};
    const trainingF1 = model.trainingInfo?.trainingF1 || modelMetrics.f1_score || 0.85;
    const testF1 = modelMetrics.f1_score || 0.85;
    const driftPercentage = Math.max(0, (trainingF1 - testF1) * 100);
    const deploymentDate = model.deployedAt ? new Date(model.deployedAt) : new Date();
    const weeksSinceDeployment = Math.floor((Date.now() - deploymentDate.getTime()) / (7 * 24 * 60 * 60 * 1000));
    const driftValues = [];
    for (let i = 0; i < 5; i++) {
      const weekOffset = Math.max(0, weeksSinceDeployment - (4 - i));
      const progressionFactor = Math.min(1, weekOffset / 12);
      driftValues.push(Math.min(this.driftThreshold * 1.2, driftPercentage * progressionFactor));
    }
    const periods = this.generatePeriods(5);
    const driftData = {
      periods: periods,
      driftValues: driftValues,
      threshold: this.driftThreshold,
      currentDrift: driftValues[driftValues.length - 1],
      isCritical: driftValues[driftValues.length - 1] >= this.driftThreshold,
      recommendations: this.getRecommendationsBasedOnDrift(driftValues[driftValues.length - 1]),
      performanceMetrics: {
        f1_score: modelMetrics.f1_score || 0,
        precision: modelMetrics.precision || 0,
        recall: modelMetrics.recall || 0,
        auc_roc: modelMetrics.auc_roc || 0
      },
      dataPoints: [],
      alertLevel: this.getAlertLevel(driftValues[driftValues.length - 1]),
      modelName: model.modelName,
      deploymentDate: model.deployedAt
    };
    this.modelDriftData.set(driftData);
    this.updateDriftAlert(driftData);
    this.isLoadingDrift.set(false);
    setTimeout(() => this.initDriftChart(), 100);
  }

  private generatePeriods(count: number): string[] {
    const periods: string[] = [];
    const now = new Date();
    for (let i = count - 1; i >= 0; i--) {
      const date = new Date(now);
      date.setDate(date.getDate() - (i * 7));
      periods.push(date.toLocaleDateString('fr-FR', { month: 'short', day: 'numeric' }));
    }
    return periods;
  }

  private getAlertLevel(driftPercentage: number): string {
    if (driftPercentage >= this.driftThreshold) return 'critical';
    if (driftPercentage >= this.driftThreshold * 0.9) return 'warning';
    if (driftPercentage >= this.driftThreshold * 0.7) return 'info';
    return 'success';
  }

  private updateDriftAlert(driftData: any): void {
    const drift = driftData.currentDrift;
    if (drift >= this.driftThreshold) {
      this.driftAlert.set({
        show: true,
        message: `ALERTE CRITIQUE: Dérive à ${drift.toFixed(1)}% - Le modèle a dépassé le seuil critique de ${this.driftThreshold}%. Un réentraînement immédiat est recommandé.`,
        driftPercentage: drift,
        level: 'critical'
      });
    } else if (drift >= this.driftThreshold) { // This part will be warning if we use a second threshold, keeping consistent with backend
      this.driftAlert.set({
        show: true,
        message: `ATTENTION: Dérive à ${drift.toFixed(1)}% - La dérive du modèle approche du seuil critique de ${this.driftThreshold}%. Planifiez un réentraînement avec les données récentes.`,
        driftPercentage: drift,
        level: 'warning'
      });
    } else if (drift >= this.driftThreshold * 0.8) {
      this.driftAlert.set({
        show: true,
        message: `Une variation de ${drift.toFixed(1)}% a été observée. Le modèle reste dans sa plage de stabilité, sous le seuil d’alerte de ${this.driftThreshold}%. Une surveillance régulière est recommandée.`,
        driftPercentage: drift,
        level: 'warning'
      });
    } else {
      this.driftAlert.set({ show: false, message: '', driftPercentage: 0, level: 'success' });
    }
  }

  private getRecommendationsBasedOnDrift(driftPercentage: number, status?: string): string[] {
    if (status === 'CRITICAL_DRIFT' || driftPercentage >= this.driftThreshold) {
      return [
        'Ré entraîner immédiatement le modèle avec les données récentes',
        'Analyser les changements dans la distribution des données entrantes',
        'Vérifier les performances sur les dernières transactions frauduleuses',
        'Réajuster les seuils de détection pour compenser la perte de précision'
      ];
    } else if (status === 'WARNING' || driftPercentage >= this.driftThreshold * 0.7) {
      return [
        'Planifier un réentraînement dans les 2 prochaines semaines',
        'Analyser les segments où la performance diminue',
        'Comparer les distributions des données actuelles vs données d\'entraînement',
        'Vérifier si de nouveaux types de fraude sont apparus'
      ];
    } else if (driftPercentage >= this.driftThreshold * 0.4) {
      return [
        'Maintenir une surveillance accrue des performances',
        'Mettre en place des alertes automatiques pour détecter les changements brusques',
        'Collecter plus de données pour le prochain entraînement'
      ];
    } else {
      return [
        'Performance stable - continuer la surveillance régulière',
        'Maintenir les journaux (logs) pour analyse future',
        'Documenter les caractéristiques actuelles du modèle'
      ];
    }
  }

  private initDriftChart(): void {
    const chartDom = document.getElementById('modelDriftChart');
    if (!chartDom || !this.modelDriftData()) return;

    if (this.driftChart) this.driftChart.dispose();
    this.driftChart = echarts.init(chartDom);
    const data = this.modelDriftData();

    const deploymentDateValue = data.deploymentDate;
    const isInvalidDate = !deploymentDateValue || deploymentDateValue === 'Never' || isNaN(new Date(deploymentDateValue).getTime());
    const displayDate = isInvalidDate ? 'Non spécifié' : new Date(deploymentDateValue).toLocaleDateString('fr-FR');

    const option = {
      title: {
        text: 'Dérive du Modèle (Model Drift)',
        subtext: `Modèle: ${data.modelName || 'Non spécifié'} | Déployé le: ${displayDate}`,
        left: 'center',
        top: 10,
        textStyle: { fontSize: 16, fontWeight: 'bold' },
        subtextStyle: { fontSize: 11, color: '#6b7280' }
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: any) => {
          if (params && params[0]) {
            const dataPoint = data.dataPoints?.find((dp: any, idx: number) => idx === params[0].dataIndex);
            return `<strong>${params[0].axisValue}</strong><br/>Dérive: ${params[0].value.toFixed(1)}%<br/>${dataPoint ? `F1-Score: ${(dataPoint.f1_score * 100).toFixed(1)}%` : ''}`;
          }
          return '';
        }
      },
      grid: {
        left: '10%',
        right: '10%',
        bottom: '10%',
        top: '18%',
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: data.periods,
        name: 'Période',
        nameLocation: 'middle',
        nameGap: 35,
        axisLabel: { fontSize: 11, fontWeight: 'bold', rotate: 0 },
        axisLine: { lineStyle: { color: '#9ca3af' } }
      },
      yAxis: {
        type: 'value',
        name: 'Dérive (%)',
        nameLocation: 'middle',
        nameGap: 45,
        min: 0,
        max: Math.max(10, Math.ceil(Math.max(...data.driftValues) * 1.2)),
        axisLabel: { fontSize: 11, formatter: (value: number) => value.toFixed(0) + '%' },
        splitLine: { lineStyle: { type: 'dashed', color: '#e5e7eb' } }
      },
      series: [{
        name: 'Dérive',
        type: 'line',
        data: data.driftValues,
        smooth: true,
        symbol: 'circle',
        symbolSize: 10,
        lineStyle: {
          color: data.currentDrift >= this.driftThreshold ? '#dc2626' : 
                 (data.currentDrift >= this.driftThreshold * 0.8 ? '#f59e0b' : '#10b981'),
          width: 3,
          shadowBlur: 10,
          shadowColor: data.currentDrift >= this.driftThreshold ? 'rgba(220, 38, 38, 0.3)' : 
                       (data.currentDrift >= this.driftThreshold * 0.8 ? 'rgba(245, 158, 11, 0.3)' : 'rgba(16, 185, 129, 0.3)')
        },
        itemStyle: {
          color: (params: any) => {
            const val = params.value;
            if (val >= this.driftThreshold) return '#dc2626';
            if (val >= this.driftThreshold * 0.8) return '#f59e0b';
            return '#10b981';
          },
          borderColor: '#ffffff',
          borderWidth: 2
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { 
              offset: 0, 
              color: data.currentDrift >= this.driftThreshold ? 'rgba(220, 38, 38, 0.2)' : 
                     (data.currentDrift >= this.driftThreshold * 0.8 ? 'rgba(245, 158, 11, 0.2)' : 'rgba(16, 185, 129, 0.2)')
            },
            { offset: 1, color: 'transparent' }
          ])
        },
        markLine: {
          data: [{
            name: 'Seuil Critique',
            yAxis: this.driftThreshold,
            lineStyle: { color: '#ef4444', width: 2, type: 'dashed' },
            label: { show: true, formatter: `Seuil: ${this.driftThreshold}%`, position: 'end', color: '#ef4444', fontWeight: 'bold' }
          }],
          symbol: 'none'
        },
        label: {
          show: true,
          position: 'top',
          formatter: (params: any) => params.value.toFixed(1) + '%',
          fontSize: 11,
          fontWeight: 'bold',
          color: (params: any) => {
            const val = params.value;
            if (val >= this.driftThreshold) return '#dc2626';
            if (val >= this.driftThreshold * 0.8) return '#f59e0b';
            return '#10b981';
          }
        }
      }]
    };

    this.driftChart.setOption(option);
    window.addEventListener('resize', () => this.driftChart?.resize());
  }

  formatPercent(value: number): string {
    return (value * 100).toFixed(1) + '%';
  }

  formatNumber(value: number): string {
    return new Intl.NumberFormat('fr-FR').format(value);
  }

  formatDuration(seconds: number): string {
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}m ${remainingSeconds}s`;
  }

  getMetricColor(value: number): string {
    if (value >= 0.8) return 'text-emerald-600';
    if (value >= 0.6) return 'text-blue-600';
    if (value >= 0.4) return 'text-amber-600';
    return 'text-red-600';
  }

  onSearch(): void {
    this.currentPage.set(0);
    this.loadModels();
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
    this.loadModels();
  }
}