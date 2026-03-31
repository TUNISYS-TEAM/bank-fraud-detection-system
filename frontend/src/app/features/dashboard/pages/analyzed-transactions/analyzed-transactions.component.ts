import { Component, OnInit, inject, signal, computed, OnDestroy, effect } from '@angular/core';
import { TransactionService, TransactionSummary } from '../../../../core/services/transaction.service';
import * as echarts from 'echarts';
import { debounceTime, distinctUntilChanged, Subject } from 'rxjs';
import { KpiCard } from '../../../../shared/cards/cards.component';

@Component({
  selector: 'app-analyzed-transactions',
  templateUrl: './analyzed-transactions.component.html',
  styleUrls: ['./analyzed-transactions.component.css']
})
export class AnalyzedTransactionsComponent implements OnInit, OnDestroy {
  private transactionService = inject(TransactionService);

  // Data signals
  transactions = signal<TransactionSummary[]>([]);
  isLoading = signal(true);
  totalElements = signal(0);
  totalPages = signal(0);
  currentPage = signal(0);
  pageSize = signal(10);
  riskFilter = signal<string>('all');

  // Stats signals
  stats = signal<any[]>([]);
  globalStats = signal<any>({
    totalAnalyzed: 0,
    alerts: 0,
    avgRiskScore: 0
  });

  // KPI Cards Data
  totalAnalyzedCard = computed<KpiCard>(() => ({
    label: 'Analyses Totales',
    value: this.globalStats().totalAnalyzed,
    icon: 'activity',
    color: '#2563eb'
  }));

  alertsCard = computed<KpiCard>(() => ({
    label: 'Alertes Critiques',
    value: this.globalStats().criticalAlerts || 0,
    icon: 'alert-triangle',
    color: '#f43f5e'
  }));

  riskScoreCard = computed<KpiCard>(() => ({
    label: 'Score de Risque',
    value: (this.globalStats().avgRiskScore * 100).toFixed(1) + '%',
    icon: 'shield',
    color: '#10b981'
  }));

  alertRateCard = computed<KpiCard>(() => {
    const total = this.globalStats().totalAnalyzed;
    const allAlerts = this.globalStats().alerts; // Now total alerts from backend
    const rate = total > 0 ? (allAlerts / total) * 100 : 0;
    return {
      label: "Taux d'Alerte",
      value: rate.toFixed(2) + '%',
      icon: 'pie-chart',
      color: '#9333ea'
    };
  });
  
  // Charts
  private scoreDistChart: any = null;
  private timeSeriesChart: any = null;
  private channelChart: any = null;
  private exposureChart: any = null;

  // Search & Filter
  searchQuery = signal('');
  private searchSubject = new Subject<string>();

  // Filtered Data (Frontend)
  filteredTransactions = computed(() => {
    let list = this.transactions();
    const risk = this.riskFilter();
    
    if (risk !== 'all') {
      const riskMapping: Record<string, string> = {
        'LOW': 'Faible',
        'MEDIUM': 'Moyen',
        'HIGH': 'Élevé',
        'CRITICAL': 'Critique'
      };
      const targetLevel = riskMapping[risk.toUpperCase()] || risk;
      list = list.filter(t => this.getRiskLevel(t.fraudScore || 0) === targetLevel);
    }
    
    const query = this.searchQuery().toLowerCase().trim();
    if (query) {
      list = list.filter(t => 
        String(t.id).includes(query) || 
        t.transactionUuid.toLowerCase().includes(query) || 
        t.clientName.toLowerCase().includes(query) ||
        t.accountNumber.toLowerCase().includes(query)
      );
    }
    
    return list;
  });

  constructor() {
    this.searchSubject.pipe(
      debounceTime(300),
      distinctUntilChanged()
    ).subscribe(query => {
      this.searchQuery.set(query);
      this.currentPage.set(0);
      this.loadTransactions();
    });

    // Effect to auto-reinit charts when data changes
    effect(() => {
      const s = this.stats();
      const t = this.transactions();
      if (s.length > 0 || t.length > 0) {
        // Use a small timeout to ensure DOM is ready if it was hidden
        setTimeout(() => this.initCharts(), 100);
      }
    });
  }

  ngOnInit(): void {
    this.loadTransactions();
    this.loadStats();
    this.loadGlobalStats();
    
    // Auto-resize charts
    window.addEventListener('resize', this.onResize.bind(this));
  }

  ngOnDestroy(): void {
    window.removeEventListener('resize', this.onResize.bind(this));
    this.disposeCharts();
  }

  loadTransactions(): void {
    this.isLoading.set(true);
    this.transactionService.getAnalyzedTransactions(this.currentPage(), this.pageSize()).subscribe({
      next: (response) => {
        this.transactions.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.isLoading.set(false);
      },
      error: (err) => {
        console.error('Error loading analyzed transactions:', err);
        this.isLoading.set(false);
      }
    });
  }

  loadStats(): void {
    this.transactionService.getTransactionStats().subscribe({
      next: (data) => {
        this.stats.set(data);
      },
      error: (err) => console.error('Error loading stats:', err)
    });
  }

  loadGlobalStats(): void {
    this.transactionService.getGlobalAnalyzedStats().subscribe({
      next: (data) => {
        console.log('Global Stats:', data);
        this.globalStats.set(data);
      },
      error: (err) => console.error('Error loading global stats:', err)
    });
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
    this.loadTransactions();
  }

  onSearchChange(query: string): void {
    this.searchSubject.next(query);
  }

  onRiskFilterChange(risk: string): void {
    this.riskFilter.set(risk);
  }

  getRiskLevel(score: number): 'Faible' | 'Moyen' | 'Élevé' | 'Critique' {
    if (score < 0.3) return 'Faible';
    if (score < 0.6) return 'Moyen';
    if (score < 0.8) return 'Élevé';
    return 'Critique';
  }

  getRiskClass(score: number): string {
    const level = this.getRiskLevel(score);
    switch (level) {
      case 'Faible': return 'bg-emerald-50 text-emerald-700 border-emerald-100';
      case 'Moyen': return 'bg-amber-50 text-amber-700 border-amber-100';
      case 'Élevé': return 'bg-orange-50 text-orange-700 border-orange-100';
      case 'Critique': return 'bg-rose-50 text-rose-700 border-rose-100';
      default: return 'bg-gray-50 text-gray-700 border-gray-100';
    }
  }

  formatDate(dateStr: string): string {
    return new Date(dateStr).toLocaleString('fr-FR');
  }

  private initCharts(): void {
    this.initScoreDistChart();
    this.initTimeSeriesChart();
    this.initChannelChart();
    this.initExposureChart();
  }

  private initScoreDistChart(): void {
    const dom = document.getElementById('scoreDistChart');
    if (!dom || this.transactions().length === 0) return;

    if (this.scoreDistChart) this.scoreDistChart.dispose();
    this.scoreDistChart = echarts.init(dom);
  
    const scores = this.transactions().map(t => t.fraudScore || 0);
    const bins = [0, 0, 0, 0]; // Low, Med, High, Crit
    scores.forEach(s => {
      if (s < 0.3) bins[0]++;
      else if (s < 0.6) bins[1]++;
      else if (s < 0.8) bins[2]++;
      else bins[3]++;
    });
  
    const option = {
      title: { text: 'Répartition du Risque', left: 'center', textStyle: { fontSize: 14 } },
      tooltip: { trigger: 'item' },
      series: [{
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: { borderRadius: 10, borderColor: '#fff', borderWidth: 2 },
        label: { show: false, position: 'center' },
        emphasis: { label: { show: true, fontSize: '16', fontWeight: 'bold' } },
        data: [
          { 
            value: bins[0], 
            name: 'Faible (<0.3)', 
            itemStyle: { 
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: '#A7F3D0' }, 
                { offset: 1, color: '#22c55e' }
              ]) 
            } 
          },
          { 
            value: bins[1], 
            name: 'Modéré (0.3-0.6)', 
            itemStyle: { 
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: '#BFDBFE' }, 
                { offset: 1, color: '#3b82f6' }
              ]) 
            } 
          },
          { 
            value: bins[2], 
            name: 'Élevé (0.6-0.8)', 
            itemStyle: { 
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: '#FFEDD5' }, 
                { offset: 1, color: '#f97316' }
              ]) 
            } 
          },
          { 
            value: bins[3], 
            name: 'Critique (>0.8)', 
            itemStyle: { 
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: '#FECACA' }, 
                { offset: 1, color: '#ef4444' }
              ]) 
            } 
          }
        ]
      }]
    };
  
    this.scoreDistChart.setOption(option);
  }

  private initTimeSeriesChart(): void {
    const dom = document.getElementById('timeSeriesChart');
    if (!dom || this.stats().length === 0) return;

    if (this.timeSeriesChart) this.timeSeriesChart.dispose();
    this.timeSeriesChart = echarts.init(dom);

    const option = {
      title: { text: 'Volume des Analyses', left: 'center', textStyle: { fontSize: 14 } },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: this.stats().map(s => new Date(s.date).getHours() + 'h') },
      yAxis: { type: 'value' },
      series: [{
        data: this.stats().map(s => s.total),
        type: 'line',
        smooth: true,
        areaStyle: { opacity: 0.1 },
        lineStyle: { width: 3, color: '#3b82f6' },
        itemStyle: { color: '#3b82f6' }
      }]
    };

    this.timeSeriesChart.setOption(option);
  }

  private initChannelChart(): void {
    const dom = document.getElementById('channelChart');
    const data = this.globalStats().channelDistribution;
    if (!dom || !data) return;

    if (this.channelChart) this.channelChart.dispose();
    this.channelChart = echarts.init(dom);

    const chartData = Object.entries(data).map(([name, value]) => ({ name, value }));

    const option = {
      title: { text: 'Répartition par Canal', left: 'center', textStyle: { fontSize: 14 } },
      tooltip: { trigger: 'item', formatter: '{b}: <strong>{c} ({d}%)</strong>' },
      series: [{
        type: 'pie',
        radius: [20, 80],
        center: ['50%', '50%'],
        roseType: 'area',
        itemStyle: { borderRadius: 8 },
        data: chartData,
        label: {
          show: true,
          fontSize: 10,
          formatter: '{b}'
        },
        color: ['#818CF8', '#6366F1', '#4F46E5', '#4338CA']
      }]
    };

    this.channelChart.setOption(option);
  }

  private initExposureChart(): void {
    const dom = document.getElementById('exposureChart');
    const exposure = this.globalStats().amountExposure;
    if (!dom || !exposure) return;

    if (this.exposureChart) this.exposureChart.dispose();
    this.exposureChart = echarts.init(dom);

    const option = {
      title: { text: 'Exposition Financière (TND)', left: 'center', textStyle: { fontSize: 14 } },
      tooltip: { 
        trigger: 'axis',
        formatter: (params: any) => {
          const p = params[0];
          return `${p.name}: <strong>${p.value.toLocaleString()} TND</strong>`;
        }
      },
      grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
      xAxis: { type: 'category', data: ['Faible', 'Moyen', 'Élevé', 'Critique'] },
      yAxis: { type: 'value', axisLabel: { formatter: (v: number) => v >= 1000 ? (v/1000) + 'k' : v } },
      series: [{
        data: [
          { 
            value: exposure[0], 
            itemStyle: { 
              color: 'rgba(34, 197, 94, 0.15)', 
              borderColor: '#22c55e',
              borderWidth: 2,
              borderRadius: [6, 6, 0, 0]
            } 
          },
          { 
            value: exposure[1], 
            itemStyle: { 
              color: 'rgba(59, 130, 246, 0.15)', 
              borderColor: '#3b82f6',
              borderWidth: 2,
              borderRadius: [6, 6, 0, 0]
            } 
          },
          { 
            value: exposure[2], 
            itemStyle: { 
              color: 'rgba(249, 115, 22, 0.15)', 
              borderColor: '#f97316',
              borderWidth: 2,
              borderRadius: [6, 6, 0, 0]
            } 
          },
          { 
            value: exposure[3], 
            itemStyle: { 
              color: 'rgba(239, 68, 68, 0.15)', 
              borderColor: '#ef4444',
              borderWidth: 2,
              borderRadius: [6, 6, 0, 0]
            } 
          }
        ],
        type: 'bar',
        barWidth: '50%',
      }]
    };

    this.exposureChart.setOption(option);
  }

  private onResize(): void {
    this.scoreDistChart?.resize();
    this.timeSeriesChart?.resize();
    this.channelChart?.resize();
    this.exposureChart?.resize();
  }

  private disposeCharts(): void {
    this.scoreDistChart?.dispose();
    this.timeSeriesChart?.dispose();
    this.channelChart?.dispose();
    this.exposureChart?.dispose();
  }
}