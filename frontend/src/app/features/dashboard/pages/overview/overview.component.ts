// overview.component.ts
import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { forkJoin, of, Subscription, interval } from 'rxjs';
import { catchError, timeout, switchMap } from 'rxjs/operators';
import { environment } from '../../../../../environments/environment';
import { AuthStore } from '../../../../core/store/auth.store';
import { SystemMetricsService, SystemMetrics, MetricsHistory } from '../../../../core/services/system-metrics.service';
import * as echarts from 'echarts';

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
  styleUrls: ['./overview.component.css']
})
export class OverviewComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private systemMetricsService = inject(SystemMetricsService);
  authStore = inject(AuthStore);
  
  // Signals for reactive state
  isLoading = signal(true);
  apiError = signal<string | null>(null);
  chartsInitialized = false;
  
  // Fraud Alert Data
  alertStats = signal<AlertStats>({
    total: 0, critical: 0, high: 0, medium: 0, low: 0,
    pending: 0, reviewed: 0, resolved: 0, falsePositive: 0
  });
  timeSeriesData = signal<TimeSeriesPoint[]>([]);
  riskDistributionData = signal<any[]>([]);
  recentAlerts = signal<any[]>([]);
  
  // System Metrics Data
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
    availableProcessors: 0
  });
  
  // Historical data for charts
  requestHistory = signal<{ time: string; value: number }[]>([]);
  responseTimeHistory = signal<{ time: string; value: number }[]>([]);
  cpuHistory = signal<{ time: string; value: number }[]>([]);
  memoryHistory = signal<{ time: string; value: number }[]>([]);
  
  private refreshSubscription: Subscription | null = null;
  private metricsSubscription: Subscription | null = null;
  
  // Chart instances
  private requestChart: any = null;
  private responseTimeChart: any = null;
  private cpuMemoryChart: any = null;
  private systemHealthChart: any = null;
  
  // Computed signals for UI
  roleDisplay = computed(() => this.authStore.userRoleLabel());
  
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
  
  roleDescription = computed(() => {
    const role = this.authStore.userRole();
    switch (role) {
      case 'ADMIN':
        return 'Vous avez une vue d\'ensemble du système avec des métriques globales et des indicateurs de performance en temps réel.';
      case 'ANALYST':
        return 'Utilisez nos outils avancés pour détecter et analyser les tentatives de fraude.';
      case 'MANAGER':
        return 'Supervisez la qualité des modèles et validez les stratégies de détection.';
      default:
        return 'Bienvenue sur votre plateforme de surveillance.';
    }
  });
  
  features = computed(() => {
    const role = this.authStore.userRole();
    if (role === 'ADMIN') {
      return [
        'Surveillance en temps réel des alertes',
        'Analyse des tendances de fraude',
        'Gestion des utilisateurs et permissions',
        'Configuration des seuils de détection',
        'Audit complet du système',
        'Rapports personnalisés'
      ];
    } else if (role === 'MANAGER') {
      return [
        'Validation et mise en production des modèles',
        'Supervision stratégique des performances',
        'Analyse du contexte des règles de détection',
        'Évaluation et optimisation du moteur de détection'
      ];
    } else {
      return [
        'Importation et filtrage des transactions',
        'Entraînement et test des modèles de détection',
        'Analyse et validation des alertes suspectes',
        'Simulation de scénarios de fraude complexes'
      ];
    }
  });
  
  // Computed KPI Cards - Only 4 cards
  kpiCards = computed(() => [
    {
      label: 'Uptime',
      value: this.formattedUptime(),
      icon: 'server',
      trend: '',
      trendUp: true,
      description: 'temps de fonctionnement',
      color: '#8b5cf6'
    },
    {
      label: 'Requêtes/s',
      value: this.systemMetrics().requestsPerSecond.toFixed(1),
      icon: 'activity',
      trend: this.calculateRequestTrend(),
      trendUp: this.systemMetrics().requestsPerSecond > (this.requestHistory()[this.requestHistory().length - 2]?.value || 0),
      description: 'moyenne en temps réel',
      color: '#06b6d4'
    },
    {
      label: 'Temps Réponse',
      value: this.systemMetrics().responseTime.toFixed(0) + ' ms',
      icon: 'clock',
      trend: this.calculateResponseTimeTrend(),
      trendUp: false,
      description: 'latence moyenne',
      color: '#f97316'
    },
    {
      label: 'CPU',
      value: this.systemMetrics().cpuUsage.toFixed(0) + '%',
      icon: 'cpu',
      trend: this.calculateCpuTrend(),
      trendUp: false,
      description: 'utilisation processeur',
      color: '#ef4444'
    }
  ]);
  
  ngOnInit(): void {
    this.loadAllData();
    this.startMetricsPolling();
    
    // Auto-refresh every 30 seconds for fraud data
    this.refreshSubscription = interval(30000).subscribe(() => {
      this.refreshData();
    });
  }
  
  ngOnDestroy(): void {
    if (this.refreshSubscription) {
      this.refreshSubscription.unsubscribe();
    }
    if (this.metricsSubscription) {
      this.metricsSubscription.unsubscribe();
    }
    this.disposeCharts();
  }
  
  viewAlert(alertUuid: string): void {
    console.log('View alert:', alertUuid);
  }
  
  private startMetricsPolling(): void {
    // Initial load
    this.loadSystemMetrics();
    this.loadMetricsHistory();
    
    // Poll every 5 seconds for real-time metrics
    this.metricsSubscription = interval(5000).pipe(
      switchMap(() => this.systemMetricsService.getSystemMetrics())
    ).subscribe({
      next: (metrics) => {
        this.systemMetrics.set(metrics);
        this.updateHistoricalData();
        this.refreshSystemCharts();
      },
      error: (error) => {
        console.error('Error fetching system metrics:', error);
      }
    });
  }
  
  private loadSystemMetrics(): void {
    this.systemMetricsService.getSystemMetrics().subscribe({
      next: (metrics) => {
        this.systemMetrics.set(metrics);
        this.updateHistoricalData();
      },
      error: (error) => {
        console.error('Error loading system metrics:', error);
        this.generateMockSystemMetrics();
      }
    });
  }
  
  private loadMetricsHistory(): void {
    this.systemMetricsService.getMetricsHistory(60).subscribe({
      next: (history) => {
        this.requestHistory.set(history.requestsPerSecond);
        this.responseTimeHistory.set(history.responseTime);
        this.cpuHistory.set(history.cpuUsage);
        this.memoryHistory.set(history.memoryUsage);
        
        // Initialize charts only once after history is loaded
        if (!this.chartsInitialized) {
          setTimeout(() => {
            this.initAllCharts();
            this.chartsInitialized = true;
          }, 200);
        } else {
          this.refreshSystemCharts();
        }
      },
      error: (error) => {
        console.error('Error loading metrics history:', error);
        this.generateMockHistory();
        if (!this.chartsInitialized) {
          setTimeout(() => {
            this.initAllCharts();
            this.chartsInitialized = true;
          }, 200);
        }
      }
    });
  }
  
  private updateHistoricalData(): void {
    const now = new Date();
    const timeStr = now.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    const metrics = this.systemMetrics();
    
    // Update request history
    const newRequestPoint = { time: timeStr, value: metrics.requestsPerSecond };
    const updatedRequests = [...this.requestHistory(), newRequestPoint];
    if (updatedRequests.length > 20) updatedRequests.shift();
    this.requestHistory.set(updatedRequests);
    
    // Update response time history
    const newResponsePoint = { time: timeStr, value: metrics.responseTime };
    const updatedResponseTimes = [...this.responseTimeHistory(), newResponsePoint];
    if (updatedResponseTimes.length > 20) updatedResponseTimes.shift();
    this.responseTimeHistory.set(updatedResponseTimes);
    
    // Update CPU history
    const newCpuPoint = { time: timeStr, value: metrics.cpuUsage };
    const updatedCpu = [...this.cpuHistory(), newCpuPoint];
    if (updatedCpu.length > 20) updatedCpu.shift();
    this.cpuHistory.set(updatedCpu);
    
    // Update memory history
    const newMemoryPoint = { time: timeStr, value: metrics.memoryUsage };
    const updatedMemory = [...this.memoryHistory(), newMemoryPoint];
    if (updatedMemory.length > 20) updatedMemory.shift();
    this.memoryHistory.set(updatedMemory);
  }
  
  private loadAllData(): void {
    this.isLoading.set(true);
    this.apiError.set(null);
    
    const apiBaseUrl = environment.apiUrl || '';
    
    forkJoin({
      recent: this.http.get<any[]>(`${apiBaseUrl}/fraud-alerts/recent?limit=10`).pipe(
        timeout(10000),
        catchError(err => {
          console.error('Recent alerts API error:', err);
          return of([]);
        })
      )
    }).subscribe({
      next: (results) => {
        if (results.recent && results.recent.length > 0) this.recentAlerts.set(results.recent);
        else this.generateMockRecentAlerts();
        
        this.isLoading.set(false);
      },
      error: (error) => {
        console.error('Error loading dashboard data:', error);
        this.apiError.set('Impossible de charger les données. Affichage des données de démonstration.');
        this.isLoading.set(false);
        
        this.generateMockRecentAlerts();
        this.generateMockSystemMetrics();
        this.generateMockHistory();
        
        if (!this.chartsInitialized) {
          setTimeout(() => {
            this.initAllCharts();
            this.chartsInitialized = true;
          }, 200);
        }
      }
    });
  }
  
  private refreshData(): void {
    const apiBaseUrl = environment.apiUrl || '';
    
    this.http.get<any[]>(`${apiBaseUrl}/fraud-alerts/recent?limit=10`)
      .pipe(catchError(() => of([])))
      .subscribe({
        next: (recent) => {
          if (recent && recent.length > 0) this.recentAlerts.set(recent);
        },
        error: (error) => {
          console.error('Error refreshing dashboard data:', error);
        }
      });
  }
  
  private generateMockSystemMetrics(): void {
    this.systemMetrics.set({
      uptime: Math.floor(Math.random() * 86400) + 3600,
      requestsPerSecond: Math.floor(Math.random() * 100) + 20,
      cpuUsage: Math.floor(Math.random() * 60) + 10,
      memoryUsage: Math.floor(Math.random() * 50) + 30,
      responseTime: Math.floor(Math.random() * 200) + 50,
      activeConnections: Math.floor(Math.random() * 50) + 10,
      diskUsage: Math.floor(Math.random() * 40) + 20,
      threadCount: Math.floor(Math.random() * 50) + 20,
      jvmVersion: '17.0.1',
      osName: 'Linux',
      osArch: 'amd64',
      availableProcessors: 8
    });
  }
  
  private generateMockHistory(): void {
    const requests: { time: string; value: number }[] = [];
    const responses: { time: string; value: number }[] = [];
    const cpus: { time: string; value: number }[] = [];
    const memories: { time: string; value: number }[] = [];
    
    for (let i = 20; i >= 0; i--) {
      const time = new Date(Date.now() - i * 60000).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
      requests.push({ time, value: Math.floor(Math.random() * 100) + 20 });
      responses.push({ time, value: Math.floor(Math.random() * 200) + 50 });
      cpus.push({ time, value: Math.floor(Math.random() * 60) + 10 });
      memories.push({ time, value: Math.floor(Math.random() * 50) + 30 });
    }
    
    this.requestHistory.set(requests);
    this.responseTimeHistory.set(responses);
    this.cpuHistory.set(cpus);
    this.memoryHistory.set(memories);
  }
  
  private generateMockRecentAlerts(): void {
    this.recentAlerts.set([
      {
        alertUuid: 'mock-1',
        createdAt: new Date().toISOString(),
        riskLevel: 'CRITICAL',
        alertType: 'AMOUNT_ANOMALY',
        fraudScore: 0.92,
        status: 'PENDING'
      },
      {
        alertUuid: 'mock-2',
        createdAt: new Date(Date.now() - 3600000).toISOString(),
        riskLevel: 'HIGH',
        alertType: 'LOCATION_ANOMALY',
        fraudScore: 0.78,
        status: 'PENDING'
      },
      {
        alertUuid: 'mock-3',
        createdAt: new Date(Date.now() - 7200000).toISOString(),
        riskLevel: 'MEDIUM',
        alertType: 'PATTERN',
        fraudScore: 0.65,
        status: 'REVIEWED'
      },
      {
        alertUuid: 'mock-4',
        createdAt: new Date(Date.now() - 10800000).toISOString(),
        riskLevel: 'LOW',
        alertType: 'LOCATION_ANOMALY',
        fraudScore: 0.34,
        status: 'RESOLVED'
      }
    ]);
  }
  
  private initAllCharts(): void {
    // Only initialize if DOM elements exist and charts not already initialized
    if (this.requestChart || this.responseTimeChart || this.cpuMemoryChart || this.systemHealthChart) {
      return;
    }
    
    this.initRequestChart();
    this.initResponseTimeChart();
    this.initCpuMemoryChart();
    this.initSystemHealthChart();
  }
  
  private refreshSystemCharts(): void {
    if (this.requestChart && !this.requestChart.isDisposed()) {
      this.updateRequestChart();
    }
    if (this.responseTimeChart && !this.responseTimeChart.isDisposed()) {
      this.updateResponseTimeChart();
    }
    if (this.cpuMemoryChart && !this.cpuMemoryChart.isDisposed()) {
      this.updateCpuMemoryChart();
    }
    if (this.systemHealthChart && !this.systemHealthChart.isDisposed()) {
      this.updateSystemHealthChart();
    }
  }
  
  private disposeCharts(): void {
    if (this.requestChart && !this.requestChart.isDisposed()) {
      this.requestChart.dispose();
      this.requestChart = null;
    }
    if (this.responseTimeChart && !this.responseTimeChart.isDisposed()) {
      this.responseTimeChart.dispose();
      this.responseTimeChart = null;
    }
    if (this.cpuMemoryChart && !this.cpuMemoryChart.isDisposed()) {
      this.cpuMemoryChart.dispose();
      this.cpuMemoryChart = null;
    }
    if (this.systemHealthChart && !this.systemHealthChart.isDisposed()) {
      this.systemHealthChart.dispose();
      this.systemHealthChart = null;
    }
    this.chartsInitialized = false;
  }
  
  private initRequestChart(): void {
    const chartDom = document.getElementById('requestChart');
    if (!chartDom) return;
    if (this.requestChart && !this.requestChart.isDisposed()) {
      this.requestChart.dispose();
    }
    this.requestChart = echarts.init(chartDom);
    this.updateRequestChart();
    window.addEventListener('resize', () => {
      if (this.requestChart && !this.requestChart.isDisposed()) {
        this.requestChart.resize();
      }
    });
  }
  
  private updateRequestChart(): void {
    if (!this.requestChart || this.requestChart.isDisposed()) return;
    const data = this.requestHistory();
    this.requestChart.setOption({
      title: { text: 'Requêtes par Seconde', left: 'center', textStyle: { fontSize: 14, fontWeight: 'bold' } },
      tooltip: { trigger: 'axis' },
      grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
      xAxis: { type: 'category', data: data.map(d => d.time), axisLabel: { rotate: 45 } },
      yAxis: { type: 'value', name: 'Requêtes/s' },
      series: [{
        name: 'RPS', type: 'line', data: data.map(d => d.value), smooth: true, color: '#06b6d4',
        areaStyle: { color: 'rgba(6, 182, 212, 0.1)' }, lineStyle: { width: 3 }, symbol: 'circle', symbolSize: 6
      }]
    }, true);
  }
  
  private initResponseTimeChart(): void {
    const chartDom = document.getElementById('responseTimeChart');
    if (!chartDom) return;
    if (this.responseTimeChart && !this.responseTimeChart.isDisposed()) {
      this.responseTimeChart.dispose();
    }
    this.responseTimeChart = echarts.init(chartDom);
    this.updateResponseTimeChart();
    window.addEventListener('resize', () => {
      if (this.responseTimeChart && !this.responseTimeChart.isDisposed()) {
        this.responseTimeChart.resize();
      }
    });
  }
  
  private updateResponseTimeChart(): void {
    if (!this.responseTimeChart || this.responseTimeChart.isDisposed()) return;
    const data = this.responseTimeHistory();
    this.responseTimeChart.setOption({
      title: { text: 'Temps de Réponse', left: 'center', textStyle: { fontSize: 14, fontWeight: 'bold' } },
      tooltip: { trigger: 'axis' },
      grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
      xAxis: { type: 'category', data: data.map(d => d.time), axisLabel: { rotate: 45 } },
      yAxis: { type: 'value', name: 'ms' },
      series: [{
        name: 'Latence', type: 'line', data: data.map(d => d.value), smooth: true, color: '#f97316',
        areaStyle: { color: 'rgba(249, 115, 22, 0.1)' }, lineStyle: { width: 3 }, symbol: 'circle', symbolSize: 6
      }]
    }, true);
  }
  
  private initCpuMemoryChart(): void {
    const chartDom = document.getElementById('cpuMemoryChart');
    if (!chartDom) return;
    if (this.cpuMemoryChart && !this.cpuMemoryChart.isDisposed()) {
      this.cpuMemoryChart.dispose();
    }
    this.cpuMemoryChart = echarts.init(chartDom);
    this.updateCpuMemoryChart();
    window.addEventListener('resize', () => {
      if (this.cpuMemoryChart && !this.cpuMemoryChart.isDisposed()) {
        this.cpuMemoryChart.resize();
      }
    });
  }
  
  private updateCpuMemoryChart(): void {
    if (!this.cpuMemoryChart || this.cpuMemoryChart.isDisposed()) return;
    const cpuData = this.cpuHistory();
    const memoryData = this.memoryHistory();
    this.cpuMemoryChart.setOption({
      title: { text: 'CPU & Mémoire', left: 'center', textStyle: { fontSize: 14, fontWeight: 'bold' } },
      tooltip: { trigger: 'axis' },
      legend: { data: ['CPU (%)', 'Mémoire (%)'], top: 30, left: 'center' },
      grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
      xAxis: { type: 'category', data: cpuData.map(d => d.time), axisLabel: { rotate: 45 } },
      yAxis: { type: 'value', name: 'Pourcentage (%)', max: 100 },
      series: [
        { name: 'CPU (%)', type: 'line', data: cpuData.map(d => d.value), smooth: true, color: '#ef4444', lineStyle: { width: 2 } },
        { name: 'Mémoire (%)', type: 'line', data: memoryData.map(d => d.value), smooth: true, color: '#3b82f6', lineStyle: { width: 2 } }
      ]
    }, true);
  }
  
  private initSystemHealthChart(): void {
    const chartDom = document.getElementById('systemHealthChart');
    if (!chartDom) return;
    if (this.systemHealthChart && !this.systemHealthChart.isDisposed()) {
      this.systemHealthChart.dispose();
    }
    this.systemHealthChart = echarts.init(chartDom);
    this.updateSystemHealthChart();
    window.addEventListener('resize', () => {
      if (this.systemHealthChart && !this.systemHealthChart.isDisposed()) {
        this.systemHealthChart.resize();
      }
    });
  }
  
  private updateSystemHealthChart(): void {
    if (!this.systemHealthChart || this.systemHealthChart.isDisposed()) return;
    const healthScore = Math.max(0, Math.min(100, 100 - (this.systemMetrics().cpuUsage * 0.3 + this.systemMetrics().memoryUsage * 0.2)));
    this.systemHealthChart.setOption({
      title: { text: 'Santé du Système', left: 'center', textStyle: { fontSize: 14, fontWeight: 'bold' } },
      series: [{
        type: 'gauge', center: ['50%', '50%'], radius: '70%', startAngle: 180, endAngle: 0,
        min: 0, max: 100, splitNumber: 5, progress: { show: true, width: 18, itemStyle: { color: this.getHealthColor(healthScore) } },
        axisLine: { lineStyle: { width: 18, color: [[healthScore / 100, this.getHealthColor(healthScore)]] } },
        axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false }, pointer: { show: false },
        detail: { offsetCenter: [0, 20], valueAnimation: true, fontSize: 20, fontWeight: 'bold' },
        title: { show: true, offsetCenter: [0, -20], fontSize: 14 },
        data: [{ value: healthScore, name: 'Score' }]
      }]
    }, true);
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
    const change = previous ? ((current - previous) / previous * 100).toFixed(0) : '0';
    return `${change}%`;
  }
  
  private calculateResponseTimeTrend(): string {
    const history = this.responseTimeHistory();
    if (history.length < 2) return '0%';
    const current = history[history.length - 1].value;
    const previous = history[history.length - 2].value;
    const change = previous ? ((current - previous) / previous * 100).toFixed(0) : '0';
    return `${change}%`;
  }
  
  private calculateCpuTrend(): string {
    const history = this.cpuHistory();
    if (history.length < 2) return '0%';
    const current = history[history.length - 1].value;
    const previous = history[history.length - 2].value;
    const change = previous ? ((current - previous) / previous * 100).toFixed(0) : '0';
    return `${change}%`;
  }
}