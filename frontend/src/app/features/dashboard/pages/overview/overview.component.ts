import { Component, inject, computed, signal, OnInit, OnDestroy, HostListener, AfterViewInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import * as echarts from 'echarts';
import { forkJoin, of, Subscription, interval } from 'rxjs';
import { catchError, timeout, switchMap } from 'rxjs/operators';

import { environment } from '../../../../../environments/environment';
import { AuthStore } from '../../../../core/store/auth.store';
import { OverviewService, OverviewStats } from '../../../../core/services/overview.service';
import { FraudAlertService } from '../../../../core/services/fraud-alert.service';
import { AuthService } from '../../../../core/services/auth.service';
import { FraudAlertResponse } from '../../../../core/models/fraud-alert.model';
import { UserRole } from '../../../../core/models/auth.model';
import { KpiCard } from '../../../../shared/cards/cards.component';
import { SystemMetricsService, SystemMetrics } from '../../../../core/services/system-metrics.service';
import { ToastrService } from 'ngx-toastr';
import { DatasetService, Dataset, DatasetType } from '../../../../core/services/dataset.service';

interface AlertStats {
  total: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
  pending: number;
  reviewed: number;
  resolved: number;
  falsePositive: number;
}

interface TimeSeriesPoint {
  date: string;
  count: number;
  criticalCount: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
}

@Component({
  selector: 'app-overview',
  templateUrl: './overview.component.html',
  styleUrl: './overview.component.css',
})
export class OverviewComponent implements OnInit, OnDestroy, AfterViewInit {
  readonly authStore = inject(AuthStore);

  private overviewService = inject(OverviewService);
  private alertService = inject(FraudAlertService);
  private authService = inject(AuthService);
  private http = inject(HttpClient);
  private systemMetricsService = inject(SystemMetricsService);
  private toastr = inject(ToastrService);
  private datasetService = inject(DatasetService);

  userRole = signal<UserRole | null>(null);
  isLoading = signal(true);
  isModalProcessing = signal(false);
  private adminChartsInitialized = false;

  @HostListener('window:resize')
  onResize(): void {
    // Only resize charts that actually exist
    this.fraudEvolutionChart?.resize();
    this.riskDistributionChart?.resize();
    if (this.isAdmin() && this.adminChartsInitialized) {
      this.requestChart?.resize();
      this.responseTimeChart?.resize();
      this.cpuMemoryChart?.resize();
      this.systemHealthChart?.resize();
    }
  }

  isAdmin = computed(() => this.userRole() === 'ADMIN');
  isManagerOrAnalyst = computed(
    () => this.userRole() === 'MANAGER' || this.userRole() === 'ANALYST'
  );

  roleDisplay = computed(() => this.authStore.userRoleLabel());

  roleDescription = computed(() => {
    const role = this.userRole();
    switch (role) {
      case 'ADMIN':
        return 'Vous avez une vue d’ensemble du système avec des métriques globales et des indicateurs de performance en temps réel.';
      case 'ANALYST':
        return 'Utilisez nos outils avancés pour détecter et analyser les tentatives de fraude.';
      case 'MANAGER':
        return 'Supervisez la qualité des modèles et validez les stratégies de détection.';
      default:
        return 'Bienvenue sur votre plateforme de surveillance.';
    }
  });

  features = computed(() => {
    const role = this.userRole();

    if (role === 'ADMIN') {
      return [
        'Surveillance en temps réel des alertes',
        'Analyse des tendances de fraude',
        'Gestion des utilisateurs et permissions',
        'Configuration des seuils de détection',
        'Audit complet du système',
        'Rapports personnalisés',
      ];
    }

    if (role === 'MANAGER') {
      return [
        'Validation et mise en production des modèles',
        'Supervision stratégique des performances',
        'Analyse du contexte des règles de détection',
        'Évaluation et optimisation du moteur de détection',
      ];
    }

    return [
      'Importation et filtrage des transactions',
      'Entraînement et test des modèles de détection',
      'Analyse et validation des alertes suspectes',
      'Simulation de scénarios de fraude complexes',
    ];
  });

  // =========================================================
  // SECTION MANAGER / ANALYST
  // =========================================================
  stats = signal<OverviewStats | null>(null);
  isLoadingCharts = signal(true);
  latestAlerts = signal<FraudAlertResponse[]>([]);
  
  // Datasets filtering
  availableDatasets = signal<Dataset[]>([]);
  selectedDatasetUuid = signal<string | null>(null);
  selectedDataset = computed(() => {
    const uuid = this.selectedDatasetUuid();
    return this.availableDatasets().find((d) => d.datasetUuid === uuid) || null;
  });
  
  prioritizedAlerts = computed(() => {
    return [...this.latestAlerts()].sort((a, b) => {
      const riskScores: Record<string, number> = { 'CRITICAL': 40, 'HIGH': 30, 'MEDIUM': 20, 'LOW': 10 };
      const statusScores: Record<string, number> = { 'PENDING': 100, 'REVIEWED': 50 };

      const aScore = (statusScores[a.status] || 0) + (riskScores[a.riskLevel] || 0);
      const bScore = (statusScores[b.status] || 0) + (riskScores[b.riskLevel] || 0);
      
      if (aScore !== bScore) {
        return bScore - aScore;
      }
      
      return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
    });
  });

  selectedAlert = signal<FraudAlertResponse | null>(null);
  hideAiInModal = signal(false);
  hideActionsInModal = signal(false);
  isDetailsOpen = signal(false);

  private fraudEvolutionChart: echarts.ECharts | null = null;
  private riskDistributionChart: echarts.ECharts | null = null;

  systemStats = computed(() => {
    const model = this.stats()?.activeModel;
    if (!model) {
      return {
        accuracy: 0,
        recall: 0,
        f1Score: 0,
        falsePositives: 0,
      };
    }
    return {
      accuracy: model.accuracy,
      recall: model.recall,
      f1Score: model.f1Score,
      falsePositives: model.falsePositives,
    };
  });

  activeAlertsCard = computed((): KpiCard => {
    const s = this.stats();
    const trend = s?.activeAlertsTrend ?? '';
    return {
      label: 'Alertes Actives',
      value: s?.activeAlerts ?? '...',
      icon: 'bell',
      trend,
      trendUp: trend.startsWith('+'),
      isStable: trend === 'Stable',
      color: '#ef4444',
      reverseTrend: true,
    };
  });

  fraudRateCard = computed((): KpiCard => {
    const s = this.stats();
    const trend = s?.fraudRateTrend ?? '';
    return {
      label: 'Taux de Fraude',
      value: s ? s.fraudRate.toFixed(1) + '%' : '...',
      icon: 'trending-up',
      trend,
      trendUp: trend.startsWith('+'),
      isStable: trend === 'Stable',
      color: '#f59e0b',
      reverseTrend: true,
    };
  });

  criticalTransactionsCard = computed((): KpiCard => {
    const s = this.stats();
    return {
      label: 'Transactions Critiques',
      value: s?.criticalTransactions ?? '...',
      icon: 'alert-octagon',
      trend: 'Action requise',
      trendUp: false,
      isStable: true,
      color: '#ef4444',
    };
  });

  activeModelCard = computed((): KpiCard => {
    const s = this.stats();
    return {
      label: 'Modèle Actif',
      value: s?.activeModel ? s.activeModel.name : '...',
      icon: 'check-circle',
      trend: s?.activeModel
        ? `${s.activeModel.accuracy.toFixed(1)}% précision`
        : undefined,
      trendUp: true,
      color: '#10b981',
      description: 'Déployé',
    };
  });

  // =========================================================
  // SECTION ADMIN
  // =========================================================
  apiError = signal<string | null>(null);
  chartsInitialized = false;

  alertStats = signal<AlertStats>({
    total: 0,
    critical: 0,
    high: 0,
    medium: 0,
    low: 0,
    pending: 0,
    reviewed: 0,
    resolved: 0,
    falsePositive: 0,
  });

  timeSeriesData = signal<TimeSeriesPoint[]>([]);
  riskDistributionData = signal<any[]>([]);
  recentAlerts = signal<any[]>([]);
  adminConfirmedAlerts = signal<any[]>([]);
  adminPageIdx = signal(0);
  adminTotalAlerts = signal(0);
  adminPageSize = signal(5);
  adminTotalPages = computed(() => Math.ceil(this.adminTotalAlerts() / this.adminPageSize()));

  systemMetrics = signal<SystemMetrics>({
    uptime: 0,
    requestsPerSecond: 0,
    cpuUsage: 0,
    memoryUsage: 0,
    responseTime: 0,
    activeConnections: 0,
    diskUsage: 0,
    threadCount: 0,
    jvmVersion: '',
    osName: '',
    osArch: '',
    availableProcessors: 0,
  });

  requestHistory = signal<{ time: string; value: number }[]>([]);
  responseTimeHistory = signal<{ time: string; value: number }[]>([]);
  cpuHistory = signal<{ time: string; value: number }[]>([]);
  memoryHistory = signal<{ time: string; value: number }[]>([]);

  private refreshSubscription: Subscription | null = null;
  private metricsSubscription: Subscription | null = null;

  private requestChart: echarts.ECharts | null = null;
  private responseTimeChart: echarts.ECharts | null = null;
  private cpuMemoryChart: echarts.ECharts | null = null;
  private systemHealthChart: echarts.ECharts | null = null;
  
  systemHealthScore = computed(() => {
    const metrics = this.systemMetrics();
    return Math.max(
      0,
      Math.min(100, 100 - (metrics.cpuUsage * 0.3 + metrics.memoryUsage * 0.2))
    );
  });

  formattedUptime = computed(() => {
    const seconds = this.systemMetrics().uptime;
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);

    if (days > 0) return `${days}j ${hours}h ${minutes}m`;
    if (hours > 0) return `${hours}h ${minutes}m`;
    if (minutes > 0) return `${minutes}m`;
    return `${seconds}s`;
  });

  adminKpiCards = computed((): KpiCard[] => [
    {
      label: 'Uptime',
      value: this.formattedUptime(),
      icon: 'server',
      trend: this.systemHealthScore().toFixed(0) + '%',
      trendUp: this.systemHealthScore() >= 80,
      description: 'temps de fonctionnement',
      color: '#8b5cf6',
    },
    {
      label: 'Requêtes/s',
      value: this.systemMetrics().requestsPerSecond.toFixed(1),
      icon: 'activity',
      trend: this.calculateRequestTrend(),
      trendUp:
        this.systemMetrics().requestsPerSecond >
        (this.requestHistory()[this.requestHistory().length - 2]?.value || 0),
      description: 'moyenne en temps réel',
      color: '#06b6d4',
    },
    {
      label: 'Temps Réponse',
      value: this.systemMetrics().responseTime.toFixed(0) + ' ms',
      icon: 'clock',
      trend: this.calculateResponseTimeTrend(),
      trendUp: false,
      description: 'latence moyenne',
      color: '#f97316',
    },
    {
      label: 'CPU',
      value: this.systemMetrics().cpuUsage.toFixed(0) + '%',
      icon: 'zap',
      trend: this.calculateCpuTrend(),
      trendUp: false,
      description: 'utilisation processeur',
      color: '#ef4444',
    },
  ]);

  ngOnInit(): void {
    const currentRole = this.authService.getCurrentUser()?.role || this.authStore.userRole() || 'ANALYST';
    this.userRole.set(currentRole as UserRole);

    if (this.isAdmin()) {
      this.loadAdminOverview();
    } else {
      this.loadFraudOverview();
    }
  }

  ngAfterViewInit(): void {
    // Admin charts will be initialized after data loads, but we can also check here
    // if admin and charts not yet initialized, try again after a short delay
    if (this.isAdmin() && !this.adminChartsInitialized) {
      setTimeout(() => {
        if (this.isAdmin() && !this.adminChartsInitialized) {
          this.initAllCharts();
        }
      }, 500);
    }
  }

  ngOnDestroy(): void {
    this.disposeFraudCharts();
    this.disposeAdminCharts();

    this.refreshSubscription?.unsubscribe();
    this.metricsSubscription?.unsubscribe();
  }

  // =========================================================
  // INIT LOADERS
  // =========================================================
  private loadFraudOverview(): void {
    this.loadStats();
    this.loadChartData();
    this.loadRecentAnomalies();
  }

  private loadAdminOverview(): void {
    this.loadAllData();
    this.startMetricsPolling();

    this.refreshSubscription = interval(30000).subscribe(() => {
      this.refreshData();
    });
  }

  // =========================================================
  // FRAUD OVERVIEW METHODS
  // =========================================================
  loadStats(): void {
    this.isLoading.set(true);
    this.overviewService.getOverviewStats().subscribe({
      next: (data) => {
        this.stats.set(data);
        this.isLoading.set(false);
      },
      error: () => {
        this.isLoading.set(false);
      },
    });
  }

  loadRecentAnomalies(): void {
    this.alertService.getRecentAnomalies(4).subscribe({
      next: (alerts) => this.latestAlerts.set(alerts),
      error: () => this.latestAlerts.set([]),
    });
  }

  loadChartData(): void {
    this.isLoadingCharts.set(true);

    this.overviewService.getTransactionTimeSeries().subscribe({
      next: (data) => {
        setTimeout(() => this.initFraudEvolutionChart(data), 100);
      },
      error: () => {
        this.isLoadingCharts.set(false);
      },
    });

    this.overviewService.getRiskDistribution().subscribe({
      next: (data) => {
        setTimeout(() => this.initRiskDistributionChart(data), 100);
        this.isLoadingCharts.set(false);
      },
      error: () => {
        this.isLoadingCharts.set(false);
      },
    });
  }

  private initFraudEvolutionChart(data: any[]): void {
    const chartDom = document.getElementById('fraudEvolutionChart');
    if (!chartDom || !data || data.length === 0) return;

    if (this.fraudEvolutionChart) {
      this.fraudEvolutionChart.dispose();
    }
    
    this.fraudEvolutionChart = echarts.init(chartDom, null, { renderer: 'svg' });

    const dates = data.map((item) => {
      const d = new Date(item.date);
      return d.getHours().toString().padStart(2, '0') + ':00';
    });

    const frauds = data.map((item) => item.fraudes || 0);
    const legitimates = data.map((item) => item.total - (item.fraudes || 0));

    this.fraudEvolutionChart.setOption({
      color: ['#3b82f6', '#f43f5e'],
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross', label: { backgroundColor: '#6a7985' } },
      },
      legend: {
        data: ['Légitimes', 'Fraudes'],
        bottom: 0,
        icon: 'circle',
        textStyle: { color: '#475569', fontWeight: 500 },
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '10%',
        containLabel: true,
      },
      xAxis: [
        {
          type: 'category',
          boundaryGap: false,
          data: dates,
          axisLine: { lineStyle: { color: '#e5e7eb' } },
          axisLabel: { color: '#9ca3af' },
        },
      ],
      yAxis: [
        {
          type: 'value',
          minInterval: 1,
          splitLine: { lineStyle: { type: 'dashed' } },
          axisLabel: { color: '#9ca3af' },
        },
      ],
      series: [
        {
          name: 'Légitimes',
          type: 'line',
          stack: 'Total',
          smooth: true,
          lineStyle: { width: 1, color: '#3b82f6' },
          showSymbol: true,
          symbolSize: 4,
          areaStyle: {
            opacity: 0.4,
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(59, 130, 246, 0.6)' },
              { offset: 1, color: 'rgba(59, 130, 246, 0.1)' },
            ]),
          },
          emphasis: { focus: 'series' },
          data: legitimates,
        },
        {
          name: 'Fraudes',
          type: 'line',
          stack: 'Total',
          smooth: true,
          lineStyle: { width: 1, color: '#f43f5e' },
          showSymbol: true,
          symbolSize: 4,
          areaStyle: {
            opacity: 0.4,
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(244, 63, 94, 0.6)' },
              { offset: 1, color: 'rgba(244, 63, 94, 0.1)' },
            ]),
          },
          emphasis: { focus: 'series' },
          data: frauds,
        },
      ],
    });
  }

  private initRiskDistributionChart(data: any[]): void {
    const chartDom = document.getElementById('riskDistributionChart');
    if (!chartDom || !data || data.length === 0) return;

    if (this.riskDistributionChart) {
      this.riskDistributionChart.dispose();
    }
    
    this.riskDistributionChart = echarts.init(chartDom, null, { renderer: 'svg' });

    const riskOrder = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
    const riskLabels = ['Faible', 'Moyen', 'Élevé', 'Critique'];

    const bgColors = [
      'rgba(34, 197, 94, 0.15)',
      'rgba(59, 130, 246, 0.15)',
      'rgba(249, 115, 22, 0.15)',
      'rgba(239, 68, 68, 0.15)',
    ];
    const borderColors = ['#22c55e', '#3b82f6', '#f97316', '#ef4444'];

    const chartData = riskOrder.map((level, index) => {
      const item = data.find((d) => d.riskLevel === level);
      return {
        value: item ? item.count : 0,
        itemStyle: {
          color: bgColors[index],
          borderColor: borderColors[index],
        },
      };
    });

    this.riskDistributionChart.setOption({
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '3%',
        containLabel: true,
      },
      xAxis: [
        {
          type: 'category',
          data: riskLabels,
          axisTick: { alignWithLabel: true },
          axisLine: { lineStyle: { color: '#e5e7eb' } },
          axisLabel: { color: '#4b5563', fontWeight: 'bold' },
        },
      ],
      yAxis: [
        {
          type: 'value',
          minInterval: 1,
          splitLine: { lineStyle: { type: 'dashed' } },
          axisLabel: { color: '#9ca3af' },
        },
      ],
      series: [
        {
          name: 'Alertes',
          type: 'bar',
          barWidth: '60%',
          data: chartData,
          itemStyle: {
            borderWidth: 2,
            borderRadius: [8, 8, 0, 0],
          },
        },
      ],
    });
  }

  private disposeFraudCharts(): void {
    this.fraudEvolutionChart?.dispose();
    this.riskDistributionChart?.dispose();
    this.fraudEvolutionChart = null;
    this.riskDistributionChart = null;
  }

  getRiskBadgeClass(risk: string): string {
    switch (risk?.toUpperCase()) {
      case 'CRITICAL':
        return 'risk-badge-critical';
      case 'HIGH':
        return 'risk-badge-high';
      case 'MEDIUM':
        return 'risk-badge-medium';
      case 'LOW':
        return 'risk-badge-low';
      default:
        return 'risk-badge-default';
    }
  }

  getRiskIcon(risk: string): string {
    switch (risk?.toUpperCase()) {
      case 'CRITICAL':
        return 'alert-octagon';
      case 'HIGH':
        return 'alert-triangle';
      case 'MEDIUM':
        return 'info';
      case 'LOW':
        return 'check-circle';
      default:
        return 'shield';
    }
  }

  getStatusBadgeClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'PENDING':
        return 'bg-amber-50 text-amber-600 border-amber-100';
      case 'REVIEWED':
        return 'bg-blue-50 text-blue-600 border-blue-100';
      case 'RESOLVED':
      case 'CONFIRMED':
        return 'bg-emerald-50 text-emerald-600 border-emerald-100';
      case 'FALSE_POSITIVE':
        return 'bg-slate-50 text-slate-600 border-slate-100';
      default:
        return 'bg-gray-50 text-gray-600 border-gray-100';
    }
  }

  translateStatus(status: string): string {
    switch (status?.toUpperCase()) {
      case 'PENDING':
        return 'En Attente';
      case 'REVIEWED':
        return 'En Cours';
      case 'RESOLVED':
      case 'CONFIRMED':
        return 'Confirmée';
      case 'FALSE_POSITIVE':
        return 'Faux Positif';
      default:
        return status;
    }
  }

  translateRisk(risk: string): string {
    switch (risk?.toUpperCase()) {
      case 'CRITICAL':
        return 'Critique';
      case 'HIGH':
        return 'Élevé';
      case 'MEDIUM':
        return 'Moyen';
      case 'LOW':
        return 'Faible';
      default:
        return risk;
    }
  }

  translateAlertType(type: string): string {
    switch (type?.toUpperCase()) {
      case 'PATTERN':
        return 'Schéma de Fraude';
      case 'LOCATION_ANOMALY':
        return 'Anomalie de Localisation';
      case 'VELOCITY':
        return 'Vélocité Inhabituelle';
      case 'AMOUNT_ANOMALY':
        return 'Montant Anormal';
      case 'NEW_DEVICE':
        return 'Nouvel Appareil';
      case 'NO_HISTORY':
        return 'Aucun Historique';
      case 'BEHAVIORAL':
        return 'Comportement Suspect';
      default:
        return type;
    }
  }

  getRiskLabel(level: string): string {
    return this.translateRisk(level);
  }

  formatTimeAgo(dateString: string): string {
    if (!dateString) return '';
    try {
      const date = new Date(dateString);
      const now = new Date();
      const diffInSeconds = Math.floor((now.getTime() - date.getTime()) / 1000);

      if (diffInSeconds < 60) return "À l'instant";
      if (diffInSeconds < 3600) return `Il y a ${Math.floor(diffInSeconds / 60)} min`;
      if (diffInSeconds < 86400) return `Il y a ${Math.floor(diffInSeconds / 3600)} h`;
      return `Il y a ${Math.floor(diffInSeconds / 86400)} j`;
    } catch {
      return '';
    }
  }

  onExamine(alert: FraudAlertResponse): void {
    this.openDetails(alert, false, false);
  }

  onAdminPageChange(page: number): void {
    this.adminPageIdx.set(page);
    this.refreshData();
  }

  getAdminAlertDisplayRange() {
    const total = this.adminTotalAlerts();
    const start = this.adminPageIdx() * this.adminPageSize() + 1;
    const end = Math.min((this.adminPageIdx() + 1) * this.adminPageSize(), total);
    return { start, end, total };
  }

  openDetails(alert: FraudAlertResponse, hideAi: boolean = false, hideActions: boolean = false): void {
    this.selectedAlert.set(alert);
    this.hideAiInModal.set(hideAi);
    this.hideActionsInModal.set(hideActions);
    this.isDetailsOpen.set(true);
  }

  closeDetails(): void {
    this.isDetailsOpen.set(false);
    this.selectedAlert.set(null);
  }

  handleAlertAction(event: {uuid: string, type: string, comment: string}): void {
    let status = 'PENDING';
    let actionTaken = '';

    if (event.type === 'VALIDE_FRAUDE') {
      status = 'CONFIRMED';
      actionTaken = 'CONFIRMED_FRAUD';
    } else if (event.type === 'REJETTE_ALERTE') {
      status = 'FALSE_POSITIVE';
      actionTaken = 'DISMISSED';
    } else if (event.type === 'MARK_AS_REVIEWED') {
      status = 'REVIEWED';
      actionTaken = 'REVIEWED';
    } else if (event.type === 'CONTACT_CLIENT') {
      this.toastr.info('Fonctionnalité de contact client en attente d\'implémentation backend.', 'Information');
      this.closeDetails();
      return;
    } else {
      return;
    }

    this.isModalProcessing.set(true);
    this.alertService.updateAlertStatus(event.uuid, {
      status: status,
      reviewComment: event.comment,
      actionTaken: actionTaken
    }).subscribe({
      next: () => {
        const alertId = this.selectedAlert()?.id;
        let message = '';
        
        if (event.type === 'VALIDE_FRAUDE') {
          message = `L'alerte d'id #${alertId} a été validée comme fraude`;
        } else if (event.type === 'REJETTE_ALERTE') {
          message = `L'alerte d'id #${alertId} a été rejetée`;
        } else if (event.type === 'MARK_AS_REVIEWED') {
          message = `L'alerte d'id #${alertId} a été marquée comme en cours d'analyse`;
        }
        
        this.toastr.success(message, 'Succès');
        this.isModalProcessing.set(false);
        this.closeDetails();
        this.loadRecentAnomalies();
        this.loadStats();
      },
      error: (err) => {
        console.error('Error updating alert status', err);
        this.toastr.error('Erreur lors de la mise à jour de l\'alerte', 'Erreur');
        this.isModalProcessing.set(false);
      }
    });
  }

  // =========================================================
  // ADMIN OVERVIEW METHODS
  // =========================================================
  viewAlert(alertUuid: string): void {}

  private startMetricsPolling(): void {
    this.loadSystemMetrics();
    this.loadMetricsHistory();

    setTimeout(() => {
      this.metricsSubscription = interval(5000)
        .pipe(switchMap(() => this.systemMetricsService.getSystemMetrics()))
        .subscribe({
          next: (metrics) => {
            this.systemMetrics.set(metrics);
            this.updateHistoricalData();
            if (this.adminChartsInitialized) {
              this.refreshSystemCharts();
            }
          },
          error: (err) => {
            console.error('Error fetching metrics:', err);
          },
        });
    }, 1000);
  }

  private loadSystemMetrics(): void {
    this.systemMetricsService.getSystemMetrics().subscribe({
      next: (metrics) => {
        this.systemMetrics.set(metrics);
        this.updateHistoricalData();
      },
      error: (err) => {
        console.error('Error loading system metrics:', err);
      },
    });
  }

  private loadMetricsHistory(): void {
    this.systemMetricsService.getMetricsHistory(60).subscribe({
      next: (history) => {
        this.requestHistory.set(history.requestsPerSecond || []);
        this.responseTimeHistory.set(history.responseTime || []);
        this.cpuHistory.set(history.cpuUsage || []);
        this.memoryHistory.set(history.memoryUsage || []);

        if (!this.adminChartsInitialized) {
          // Wait a bit for the DOM to be ready, then initialize charts
          setTimeout(() => {
            this.initAllCharts();
          }, 200);
        } else {
          this.refreshSystemCharts();
        }
      },
      error: (err) => {
        console.error('Error loading metrics history:', err);
      },
    });
  }

  private updateHistoricalData(): void {
    const now = new Date();
    const timeStr = now.toLocaleTimeString('fr-FR', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    const metrics = this.systemMetrics();

    const updatedRequests = [...this.requestHistory(), { time: timeStr, value: metrics.requestsPerSecond }];
    if (updatedRequests.length > 20) updatedRequests.shift();
    this.requestHistory.set(updatedRequests);

    const updatedResponseTimes = [...this.responseTimeHistory(), { time: timeStr, value: metrics.responseTime }];
    if (updatedResponseTimes.length > 20) updatedResponseTimes.shift();
    this.responseTimeHistory.set(updatedResponseTimes);

    const updatedCpu = [...this.cpuHistory(), { time: timeStr, value: metrics.cpuUsage }];
    if (updatedCpu.length > 20) updatedCpu.shift();
    this.cpuHistory.set(updatedCpu);

    const updatedMemory = [...this.memoryHistory(), { time: timeStr, value: metrics.memoryUsage }];
    if (updatedMemory.length > 20) updatedMemory.shift();
    this.memoryHistory.set(updatedMemory);
  }

  private loadAllData(): void {
    this.isLoading.set(true);
    this.apiError.set(null);

    const apiBaseUrl = environment.apiUrl;
    const requests: any = {
      recent: this.http.get<any[]>(`${apiBaseUrl}/fraud-alerts/recent?limit=10`).pipe(
        timeout(10000),
        catchError(() => of([]))
      )
    };

    if (this.isAdmin()) {
      const datasetParam = this.selectedDatasetUuid() ? `&datasetUuid=${this.selectedDatasetUuid()}` : '';
      requests.confirmed = this.http.get<any>(`${apiBaseUrl}/fraud-alerts?status=CONFIRMED&page=${this.adminPageIdx()}&size=${this.adminPageSize()}&sortBy=createdAt&sortDirection=DESC${datasetParam}`).pipe(
        timeout(10000),
        catchError(() => of({ content: [], totalElements: 0 }))
      );
      
      // Also fetch datasets for the dropdown if not already loaded
      if (this.availableDatasets().length === 0) {
        requests.datasets = this.datasetService.list({ size: 100, type: DatasetType.TRANSACTIONS_VAL }).pipe(
          catchError(() => of({ content: [] }))
        );
      }
    }

    forkJoin(requests).subscribe({
      next: (results: any) => {
        this.recentAlerts.set(results.recent || []);
        if (results.confirmed) {
          this.adminConfirmedAlerts.set(results.confirmed.content || []);
          this.adminTotalAlerts.set(results.confirmed.totalElements || 0);
        }
        if (results.datasets) {
          this.availableDatasets.set(results.datasets.content || []);
        }
        this.isLoading.set(false);
      },
      error: (err) => {
        console.error('Error loading all data:', err);
        this.apiError.set('Impossible de charger les données.');
        this.isLoading.set(false);
      },
    });
  }

  private refreshData(): void {
    const apiBaseUrl = environment.apiUrl;

    const requests: any = {
      recent: this.http.get<any[]>(`${apiBaseUrl}/fraud-alerts/recent?limit=10`).pipe(catchError(() => of([])))
    };

    if (this.isAdmin()) {
      const datasetParam = this.selectedDatasetUuid() ? `&datasetUuid=${this.selectedDatasetUuid()}` : '';
      requests.confirmed = this.http.get<any>(`${apiBaseUrl}/fraud-alerts?status=CONFIRMED&page=${this.adminPageIdx()}&size=${this.adminPageSize()}&sortBy=createdAt&sortDirection=DESC${datasetParam}`).pipe(catchError(() => of({ content: [], totalElements: 0 })));
    }

    forkJoin(requests).subscribe({
      next: (results: any) => {
        if (results.recent && results.recent.length > 0) this.recentAlerts.set(results.recent);
        if (results.confirmed) {
          this.adminConfirmedAlerts.set(results.confirmed.content || []);
          this.adminTotalAlerts.set(results.confirmed.totalElements || 0);
        }
      },
      error: (err) => {
        console.error('Error refreshing data:', err);
      },
    });
  }

  // =========================================================
  // ADMIN CHART METHODS (guarded by isAdmin())
  // =========================================================
  private initAllCharts(): void {
    if (!this.isAdmin()) return;
    // Avoid double initialization
    if (this.adminChartsInitialized) return;

    // Check if the required DOM elements exist; if not, retry after a short delay
    const requestEl = document.getElementById('requestChart');
    const responseEl = document.getElementById('responseTimeChart');
    const cpuEl = document.getElementById('cpuMemoryChart');
    const healthEl = document.getElementById('systemHealthChart');
    if (!requestEl || !responseEl || !cpuEl || !healthEl) {
      console.debug('Admin chart containers not yet in DOM, will retry');
      setTimeout(() => this.initAllCharts(), 200);
      return;
    }

    this.initRequestChart();
    this.initResponseTimeChart();
    this.initCpuMemoryChart();
    this.initSystemHealthChart();
    this.adminChartsInitialized = true;
  }

  private refreshSystemCharts(): void {
    if (!this.isAdmin() || !this.adminChartsInitialized) return;
    this.updateRequestChart();
    this.updateResponseTimeChart();
    this.updateCpuMemoryChart();
    this.updateSystemHealthChart();
  }

  private disposeAdminCharts(): void {
    if (!this.isAdmin()) return;
    this.requestChart?.dispose();
    this.responseTimeChart?.dispose();
    this.cpuMemoryChart?.dispose();
    this.systemHealthChart?.dispose();

    this.requestChart = null;
    this.responseTimeChart = null;
    this.cpuMemoryChart = null;
    this.systemHealthChart = null;
    this.adminChartsInitialized = false;
  }

  private initRequestChart(): void {
    const chartDom = document.getElementById('requestChart');
    if (!chartDom) return; // silently skip, already checked in initAllCharts

    if (this.requestChart) {
      this.requestChart.dispose();
    }
    
    this.requestChart = echarts.init(chartDom);
    this.updateRequestChart();

    window.addEventListener('resize', () => {
      this.requestChart?.resize();
    });
  }

  private updateRequestChart(): void {
    if (!this.requestChart) return;

    const data = this.requestHistory();
    if (data.length === 0) return;

    this.requestChart.setOption(
      {
        title: {
          text: 'Requêtes par Seconde',
          left: 'center',
          top: 0,
          textStyle: { fontSize: 14, fontWeight: 'bold', color: '#334155' }
        },
        tooltip: { trigger: 'axis' },
        grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
        xAxis: { type: 'category', data: data.map((d) => d.time), axisLabel: { rotate: 45 } },
        yAxis: { type: 'value', name: 'Requêtes/s' },
        series: [
          {
            name: 'RPS',
            type: 'line',
            data: data.map((d) => d.value),
            smooth: true,
            color: '#06b6d4',
            areaStyle: { color: 'rgba(6, 182, 212, 0.1)' },
            lineStyle: { width: 3 },
            symbol: 'circle',
            symbolSize: 6,
          },
        ],
      },
      true
    );
  }

  private initResponseTimeChart(): void {
    const chartDom = document.getElementById('responseTimeChart');
    if (!chartDom) return;

    if (this.responseTimeChart) {
      this.responseTimeChart.dispose();
    }
    
    this.responseTimeChart = echarts.init(chartDom);
    this.updateResponseTimeChart();

    window.addEventListener('resize', () => {
      this.responseTimeChart?.resize();
    });
  }

  private updateResponseTimeChart(): void {
    if (!this.responseTimeChart) return;

    const data = this.responseTimeHistory();
    if (data.length === 0) return;

    this.responseTimeChart.setOption(
      {
        title: {
          text: 'Temps de Réponse',
          left: 'center',
          top: 0,
          textStyle: { fontSize: 14, fontWeight: 'bold', color: '#334155' }
        },
        tooltip: { trigger: 'axis' },
        grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
        xAxis: { type: 'category', data: data.map((d) => d.time), axisLabel: { rotate: 45 } },
        yAxis: { type: 'value', name: 'ms' },
        series: [
          {
            name: 'Latence',
            type: 'line',
            data: data.map((d) => d.value),
            smooth: true,
            color: '#f97316',
            areaStyle: { color: 'rgba(249, 115, 22, 0.1)' },
            lineStyle: { width: 3 },
            symbol: 'circle',
            symbolSize: 6,
          },
        ],
      },
      true
    );
  }

  private initCpuMemoryChart(): void {
    const chartDom = document.getElementById('cpuMemoryChart');
    if (!chartDom) return;

    if (this.cpuMemoryChart) {
      this.cpuMemoryChart.dispose();
    }
    
    this.cpuMemoryChart = echarts.init(chartDom);
    this.updateCpuMemoryChart();

    window.addEventListener('resize', () => {
      this.cpuMemoryChart?.resize();
    });
  }

  private updateCpuMemoryChart(): void {
    if (!this.cpuMemoryChart) return;

    const cpuData = this.cpuHistory();
    const memoryData = this.memoryHistory();
    
    if (cpuData.length === 0 && memoryData.length === 0) return;

    this.cpuMemoryChart.setOption(
      {
        title: {
          text: 'CPU & Mémoire',
          left: 'center',
          top: 0,
          textStyle: { fontSize: 14, fontWeight: 'bold', color: '#334155' }
        },
        tooltip: { trigger: 'axis' },
        legend: { data: ['CPU (%)', 'Mémoire (%)'], top: 30, left: 'center', icon: 'circle' },
        grid: { left: '3%', right: '4%', bottom: '3%', top: '20%', containLabel: true },
        xAxis: { type: 'category', data: cpuData.map((d) => d.time), axisLabel: { rotate: 45 } },
        yAxis: { type: 'value', name: 'Pourcentage (%)', max: 100 },
        series: [
          {
            name: 'CPU (%)',
            type: 'line',
            data: cpuData.map((d) => d.value),
            smooth: true,
            color: '#ef4444',
            lineStyle: { width: 2 },
          },
          {
            name: 'Mémoire (%)',
            type: 'line',
            data: memoryData.map((d) => d.value),
            smooth: true,
            color: '#3b82f6',
            lineStyle: { width: 2 },
          },
        ],
      },
      true
    );
  }

  private initSystemHealthChart(): void {
    const chartDom = document.getElementById('systemHealthChart');
    if (!chartDom) return;

    if (this.systemHealthChart) {
      this.systemHealthChart.dispose();
    }
    
    this.systemHealthChart = echarts.init(chartDom);
    this.updateSystemHealthChart();

    window.addEventListener('resize', () => {
      this.systemHealthChart?.resize();
    });
  }

  private updateSystemHealthChart(): void {
    if (!this.systemHealthChart) return;

    const healthScore = this.systemHealthScore();

    this.systemHealthChart.setOption(
      {
        title: {
          text: 'Santé du Système',
          left: 'center',
          top: 0,
          textStyle: { fontSize: 14, fontWeight: 'bold', color: '#334155' }
        },
        series: [
          {
            type: 'gauge',
            center: ['50%', '65%'],
            radius: '80%',
            startAngle: 180,
            endAngle: 0,
            min: 0,
            max: 100,
            splitNumber: 5,
            progress: {
              show: true,
              width: 22,
              itemStyle: { 
                color: this.getHealthColor(healthScore),
                borderRadius: 10 
              },
              roundCap: true
            },
            axisLine: {
              lineStyle: {
                width: 22,
                color: [[1, '#f1f5f9']],
                shadowBlur: 10,
                shadowColor: 'rgba(0,0,0,0.02)'
              },
            },
            axisTick: { show: false },
            splitLine: { show: false },
            axisLabel: { show: false },
            pointer: { show: false },
            detail: {
              offsetCenter: [0, 20],
              valueAnimation: true,
              fontSize: 30,
              fontWeight: '900',
              formatter: '{value}%',
              color: '#1e293b'
            },
            title: { 
              show: true, 
              offsetCenter: [0, -35], 
              fontSize: 14, 
              fontWeight: 'bold', 
              color: '#94a3b8'
            },
            data: [{ value: Number(healthScore.toFixed(1)), name: 'SCORE DE STABILITÉ' }],
          },
        ],
      },
      true
    );
  }

  private getHealthColor(score: number): string {
    if (score >= 80) return '#10b981';
    if (score >= 60) return '#f59e0b';
    return '#ef4444';
  }

  private calculateRequestTrend(): string {
    const history = this.requestHistory();
    if (history.length < 2) return '0%';
    const current = history[history.length - 1].value;
    const previous = history[history.length - 2].value;
    const change = previous ? ((current - previous) / previous) * 100 : 0;
    return `${change.toFixed(0)}%`;
  }

  private calculateResponseTimeTrend(): string {
    const history = this.responseTimeHistory();
    if (history.length < 2) return '0%';
    const current = history[history.length - 1].value;
    const previous = history[history.length - 2].value;
    const change = previous ? ((current - previous) / previous) * 100 : 0;
    return `${change.toFixed(0)}%`;
  }

  private calculateCpuTrend(): string {
    const history = this.cpuHistory();
    if (history.length < 2) return '0%';
    const current = history[history.length - 1].value;
    const previous = history[history.length - 2].value;
    const change = previous ? ((current - previous) / previous) * 100 : 0;
    return `${change.toFixed(0)}%`;
  }

  onDatasetFilterChange(uuid: string | null): void {
    this.selectedDatasetUuid.set(uuid === 'all' ? null : uuid);
    this.adminPageIdx.set(0); // Reset to first page
    this.refreshData();
  }
}