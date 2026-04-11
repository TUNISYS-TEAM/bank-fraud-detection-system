import { Component, OnInit, signal, computed, inject, OnDestroy } from '@angular/core';
import { SimulationService } from '../../../../core/services/simulation.service';
import { SimulationRequest, SimulationResponse, ModelSummaryResponse } from '../../../../core/models/ml-models.models';
import { ToastrService } from 'ngx-toastr';
import * as echarts from 'echarts';

@Component({
  selector: 'app-simulation',
  templateUrl: './simulation.component.html',
  styleUrl: './simulation.component.css'
})
export class SimulationComponent implements OnInit, OnDestroy {
  private simulationService = inject(SimulationService);
  private toastr = inject(ToastrService);
  Math = Math;

  // State Signals
  isLoadingModels = signal(false);
  isSimulating = signal(false);
  availableModels = signal<ModelSummaryResponse[]>([]);
  simulationResult = signal<SimulationResponse | null>(null);

  // Form Fields
  selectedModelUuid = signal<string>('');
  selectedScenario = signal<string>('mixed');
  transactionCount = signal<number>(1000);
  fraudRatio = signal<number>(0.05);
  periodHours = signal<number>(24);
  timeDistribution = signal<string>('realistic');
  randomSeed = signal<number>(42);

  // Computed Values for "Expected Metrics"
  expectedFrauds = computed(() => Math.round(this.transactionCount() * this.fraudRatio()));

  selectedModelRecall = computed(() => {
    const model = this.availableModels().find(m => m.modelUuid === this.selectedModelUuid());
    return model?.recall || 0.96;
  });

  expectedDetections = computed(() => Math.round(this.expectedFrauds() * this.selectedModelRecall()));

  estimatedDuration = computed(() => {
    const base = 0.0;
    const perTx = 0.018;
    const scenarioPenalty = this.selectedScenario() === 'sophisticated' ? 1.4 : 1.0;
    const val = (base + (this.transactionCount() * perTx)) * scenarioPenalty;

    if (val < 60) {
      return `${Math.round(val)}s`;
    } else {
      const minutes = Math.floor(val / 60);
      const seconds = Math.round(val % 60);
      return `${minutes}m ${seconds}s`;
    }
  });

  // Results State
  activeTab = signal('performance');

  selectedModel = computed(() => {
    const uuid = this.selectedModelUuid();
    return this.availableModels().find(m => m.modelUuid === uuid);
  });

  // Results Cards Data
  f1ScoreCard = computed(() => ({
    label: 'Score F1 Global',
    value: this.simulationResult() ? `${(this.simulationResult()!.f1Score * 100).toFixed(1)}%` : '...',
    icon: 'target',
    color: '#3b82f6' // Blue
  }));

  detectionRateCard = computed(() => ({
    label: 'Taux de Détection',
    value: this.simulationResult() ? `${(this.simulationResult()!.detectionRate * 100).toFixed(1)}%` : '...',
    icon: 'activity',
    color: '#10b981' // Emerald
  }));

  fraudsInjectedCard = computed(() => ({
    label: 'Fraudes Injectées',
    value: this.simulationResult()?.totalFraudsInjected ?? '...',
    icon: 'alert-triangle',
    color: '#ef4444' // Red
  }));

  detectedFraudsCard = computed(() => ({
    label: 'Dét. Réussies',
    value: this.simulationResult()?.truePositives ?? '...',
    icon: 'check-circle',
    color: '#10b981' // Emerald
  }));

  // Progress & Timer
  simulationProgress = signal(0);
  simulationTime = signal(0);
  private progressInterval: any;

  // Pagination & Filters State
  currentPage = signal(1);
  pageSize = 20;

  filterType = signal<string>('');
  filterChannel = signal<string>('');
  filterRegion = signal<string>('');
  filterRisk = signal<string>('');

  // unique options for filters
  types = computed(() => {
    const res = this.simulationResult();
    if (!res) return [];
    return [...new Set(res.topSuspiciousTransactions.map(tx => tx.category))].sort();
  });

  channels = computed(() => {
    const res = this.simulationResult();
    if (!res) return [];
    return [...new Set(res.topSuspiciousTransactions.map(tx => tx.channel))].sort();
  });

  regions = computed(() => {
    const res = this.simulationResult();
    if (!res) return [];
    return [...new Set(res.topSuspiciousTransactions.map(tx => tx.region))].sort();
  });

  riskLevels = computed(() => ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']);

  // Results Logic
  filteredTransactions = computed(() => {
    const res = this.simulationResult();
    if (!res) return [];

    return res.topSuspiciousTransactions.filter(tx => {
      const matchType = !this.filterType() || tx.category === this.filterType();
      const matchChannel = !this.filterChannel() || tx.channel === this.filterChannel();
      const matchRegion = !this.filterRegion() || tx.region === this.filterRegion();
      const matchRisk = !this.filterRisk() || tx.riskLevel === this.filterRisk();
      return matchType && matchChannel && matchRegion && matchRisk;
    });
  });

  paginatedTransactions = computed(() => {
    const start = (this.currentPage() - 1) * this.pageSize;
    const end = start + this.pageSize;
    return this.filteredTransactions().slice(start, end);
  });

  totalPages = computed(() => {
    return Math.ceil(this.filteredTransactions().length / this.pageSize);
  });

  // Comparison & Robustness Stats
  comparisonStats = computed(() => {
    const res = this.simulationResult();
    const model = this.selectedModel();
    if (!res || !model) return null;

    const simF1 = res.f1Score;
    const recallVal = model.recall || 0;
    const precisionVal = model.precision || 0;
    const refF1 = model.f1Score || (recallVal * precisionVal * 2) / (recallVal + precisionVal || 1) || 0;
    
    const diff = simF1 - refF1;
    const pctDiff = refF1 > 0 ? (diff / refF1) * 100 : 0;
    
    // Robustness: How close is simulation performance to training
    const robustness = Math.max(0, 100 - Math.abs(pctDiff));

    return {
      f1Drift: pctDiff,
      robustnessIndex: robustness,
      isPositive: pctDiff >= 0
    };
  });

  // Charts
  private riskChart: any = null;

  ngOnInit(): void {
    this.loadModels();
  }

  ngOnDestroy(): void {
    this.stopProgressTimer();
    if (this.riskChart) {
      this.riskChart.dispose();
    }
  }

  loadModels(): void {
    this.isLoadingModels.set(true);
    this.simulationService.getAvailableModels().subscribe({
      next: (models) => {
        this.availableModels.set(models);
        this.isLoadingModels.set(false);
      },
      error: (err) => {
        this.toastr.error('Erreur lors du chargement des modèles');
        this.isLoadingModels.set(false);
      }
    });
  }

  runSimulation(): void {
    if (!this.selectedModelUuid()) {
      this.toastr.warning('Veuillez sélectionner un modèle');
      return;
    }

    const request: SimulationRequest = {
      modelUuid: this.selectedModelUuid(),
      scenario: this.selectedScenario(),
      transactionCount: this.transactionCount(),
      fraudRatio: this.fraudRatio(),
      periodHours: this.periodHours(),
      timeDistribution: this.timeDistribution()
    };

    this.isSimulating.set(true);
    this.simulationResult.set(null);
    this.currentPage.set(1);
    this.startProgressTimer();

    // Auto-scroll to progress bar
    setTimeout(() => this.scrollTo('#progressSection'), 100);

    this.simulationService.runSimulation(request).subscribe({
      next: (result) => {
        this.simulationProgress.set(100);
        setTimeout(() => {
          this.simulationResult.set(result);
          this.isSimulating.set(false);
          this.stopProgressTimer();
          this.toastr.success('Simulation terminée avec succès');

          // Auto-scroll to results
          setTimeout(() => {
            this.initCharts();
            this.scrollTo('#resultsSection');
          }, 100);
        }, 500);
      },
      error: (err) => {
        this.toastr.error('La simulation a échoué');
        this.isSimulating.set(false);
        this.stopProgressTimer();
      }
    });
  }

  private startProgressTimer(): void {
    this.simulationProgress.set(0);
    this.simulationTime.set(0);
    this.stopProgressTimer();

    this.progressInterval = setInterval(() => {
      this.simulationTime.update(t => t + 1);

      // Artificial progress logic (every second)
      const current = this.simulationProgress();
      if (current < 30) {
        this.simulationProgress.update(p => p + 10);
      } else if (current < 70) {
        this.simulationProgress.update(p => p + 5);
      } else if (current < 95) {
        this.simulationProgress.update(p => p + 1);
      }
    }, 1000);
  }

  formatTime(seconds: number): string {
    const mm = Math.floor(seconds / 60).toString().padStart(2, '0');
    const ss = (seconds % 60).toString().padStart(2, '0');
    return `${mm}:${ss}`;
  }

  private stopProgressTimer(): void {
    if (this.progressInterval) {
      clearInterval(this.progressInterval);
      this.progressInterval = null;
    }
  }

  private initCharts(): void {
    const result = this.simulationResult();
    if (!result) return;

    this.initRiskChart(result);
    this.initVolumeChart(result);
    this.initChannelsChart(result);
    this.initExposureChart(result);
  }

  private initRiskChart(result: SimulationResponse): void {
    const chartDom = document.getElementById('riskDistributionChart');
    if (!chartDom) return;

    if (this.riskChart) this.riskChart.dispose();
    this.riskChart = echarts.init(chartDom);

    const data = Object.entries(result.riskDistribution)
      .filter(([name]) => name !== 'VERY_LOW')
      .map(([name, value]) => ({
        name: this.getRiskLabelFrench(name),
        value
      }));

    this.riskChart.setOption({
      tooltip: {
        trigger: 'item',
        backgroundColor: '#ffffff',
        borderWidth: 0,
        padding: 12,
        borderRadius: 12,
        extraCssText: 'box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);',
        textStyle: { color: '#1e293b', fontSize: 12 },
        formatter: (params: any) => `
          <div class="flex flex-col gap-1">
            <span class="text-[10px] font-black text-slate-400 uppercase tracking-widest">${params.name}</span>
            <div class="flex items-center gap-2">
              <span class="w-2 h-2 rounded-full" style="background:${params.color.colorStops ? params.color.colorStops[0].color : params.color}"></span>
              <span class="text-sm font-bold text-slate-800">${params.value} transactions</span>
              <span class="text-xs font-medium text-slate-500">(${params.percent}%)</span>
            </div>
          </div>
        `
      },
      legend: { bottom: '0%', left: 'center', icon: 'circle', textStyle: { color: '#64748b', fontSize: 10 } },
      series: [
        {
          name: 'Répartition du Risque',
          type: 'pie',
          radius: ['45%', '70%'],
          center: ['50%', '50%'],
          avoidLabelOverlap: false,
          itemStyle: { borderRadius: 12, borderColor: '#fff', borderWidth: 2 },
          emphasis: {
            scale: true,
            scaleSize: 8,
            itemStyle: { shadowBlur: 15, shadowColor: 'rgba(0,0,0,0.1)' }
          },
          label: { show: false, position: 'center' },
          labelLine: { show: false },
          data: data.map(d => ({
            ...d,
            itemStyle: { color: this.getGradientByRiskLabel(d.name) }
          }))
        }
      ]
    });
  }

  private initVolumeChart(result: SimulationResponse): void {
    const chartDom = document.getElementById('volumeChart');
    if (!chartDom) return;
    const chart = echarts.init(chartDom);

    const totalHours = this.periodHours();
    const avg = result.totalTransactions / totalHours;

    // Generate labels and data based on period
    const labels: string[] = [];
    const data: number[] = [];

    for (let i = 0; i < totalHours; i++) {
      if (totalHours <= 24) {
        labels.push(`${i}h`);
      } else if (totalHours <= 72) {
        // For 3 days, show Day X - H
        const day = Math.floor(i / 24) + 1;
        const hour = i % 24;
        if (hour === 0) labels.push(`J${day}`);
        else labels.push(`${hour}h`);
      } else {
        // For 7 days, mostly show Days
        const day = Math.floor(i / 24) + 1;
        const hour = i % 24;
        if (hour === 0) labels.push(`J${day}`);
        else labels.push(`${hour}h`);
      }

      // Time Distribution Factor Logic
      let timeFactor = 1.0;
      const dist = this.timeDistribution();

      if (dist === 'realistic') {
        // Sinusoidal wave (Day/Night cycle) peaking at 14h
        timeFactor = 0.5 + Math.sin((i - 8) * Math.PI / 12) * 0.4;
      } else if (dist === 'burst') {
        // High spikes every ~10 units, very low otherwise
        const isPeak = (i % 10 === 0) || (i % 10 === 1);
        timeFactor = isPeak ? 3.0 : 0.3;
      } else {
        // Uniform
        timeFactor = 1.0;
      }

      data.push(Math.floor(avg * timeFactor * (0.8 + Math.random() * 0.4)));
    }

    chart.setOption({
      tooltip: {
        trigger: 'axis',
        backgroundColor: '#ffffff',
        borderWidth: 0,
        padding: 12,
        borderRadius: 12,
        extraCssText: 'box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1);',
        formatter: (params: any) => {
          const p = params[0];
          return `
            <div class="flex flex-col gap-1">
              <span class="text-[10px] font-black text-slate-400 uppercase tracking-widest">${p.name}</span>
              <div class="flex items-center gap-2">
                <span class="text-sm font-bold text-blue-600">${p.value} analyses</span>
              </div>
            </div>`;
        }
      },
      grid: { left: '5%', right: '5%', bottom: '10%', top: '5%', containLabel: true },
      xAxis: {
        type: 'category',
        data: labels,
        axisLine: { show: false },
        axisLabel: {
          interval: totalHours > 48 ? 23 : (totalHours > 24 ? 5 : 2),
          fontSize: 10,
          color: '#94a3b8'
        }
      },
      yAxis: { type: 'value', splitLine: { lineStyle: { type: 'dashed', color: '#f1f5f9' } } },
      series: [{
        data: data,
        type: 'line',
        smooth: true,
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(59, 130, 246, 0.25)' },
            { offset: 1, color: 'rgba(59, 130, 246, 0)' }
          ])
        },
        lineStyle: { width: 3, color: '#3b82f6' },
        showSymbol: false,
        emphasis: { lineStyle: { width: 4 } }
      }]
    });
  }

  private initChannelsChart(result: SimulationResponse): void {
    const chartDom = document.getElementById('channelsChart');
    if (!chartDom) return;
    const chart = echarts.init(chartDom);

    const channels: Record<string, number> = {};
    result.topSuspiciousTransactions.forEach(tx => {
      channels[tx.channel] = (channels[tx.channel] || 0) + 1;
    });

    const gradients = [
      ['#60a5fa', '#3b82f6'], // Blue
      ['#818cf8', '#6366f1'], // Indigo
      ['#34d399', '#10b981'], // Emerald
      ['#fbbf24', '#f59e0b'], // Amber
      ['#fb7185', '#f43f5e'], // Rose
      ['#a78bfa', '#8b5cf6']  // Violet
    ];

    chart.setOption({
      tooltip: {
        trigger: 'item',
        backgroundColor: '#ffffff',
        borderWidth: 0,
        padding: 12,
        borderRadius: 12,
        extraCssText: 'box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1);',
        formatter: (params: any) => `
          <div class="flex flex-col gap-1">
            <span class="text-[10px] font-black text-slate-400 uppercase tracking-widest">${params.name}</span>
            <div class="flex items-center gap-2">
              <span class="text-sm font-bold text-slate-800">${params.value} sources</span>
              <span class="text-xs font-medium text-slate-500">(${params.percent}%)</span>
            </div>
          </div>
        `
      },
      legend: { bottom: '0%', left: 'center', icon: 'circle', textStyle: { color: '#64748b', fontSize: 10 } },
      series: [{
        name: 'Canaux',
        type: 'pie',
        roseType: 'area',
        radius: ['15%', '70%'],
        center: ['50%', '50%'],
        itemStyle: { borderRadius: 12, borderColor: '#fff', borderWidth: 2 },
        emphasis: {
          scale: true,
          scaleSize: 10,
          itemStyle: { shadowBlur: 20, shadowColor: 'rgba(0,0,0,0.15)' }
        },
        data: Object.entries(channels).map(([name, value], idx) => ({
          name,
          value,
          itemStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: gradients[idx % gradients.length][0] },
              { offset: 1, color: gradients[idx % gradients.length][1] }
            ])
          }
        })),
        label: { show: true, fontSize: 10, color: '#64748b' }
      }]
    });
  }

  private initExposureChart(result: SimulationResponse): void {
    const chartDom = document.getElementById('exposureChart');
    if (!chartDom) return;
    const chart = echarts.init(chartDom);

    const exposure: Record<string, number> = {};
    result.topSuspiciousTransactions.forEach(tx => {
      if (tx.riskLevel !== 'VERY_LOW') {
        exposure[tx.riskLevel] = (exposure[tx.riskLevel] || 0) + tx.amount;
      }
    });

    const categories = Object.keys(exposure).map(risk => this.getRiskLabelFrench(risk));
    const values = Object.values(exposure);

    chart.setOption({
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        backgroundColor: '#ffffff',
        borderWidth: 0,
        padding: 12,
        borderRadius: 12,
        extraCssText: 'box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1);',
        formatter: (p: any) => `
          <div class="flex flex-col gap-1">
            <span class="text-[10px] font-black text-slate-400 uppercase tracking-widest">${p[0].name}</span>
            <div class="flex items-center gap-2">
              <span class="text-sm font-bold text-slate-800">${Math.round(p[0].value).toLocaleString()} TND</span>
            </div>
          </div>`
      },
      grid: { left: '5%', right: '10%', bottom: '5%', top: '5%', containLabel: true },
      xAxis: { type: 'value', splitLine: { show: false }, axisLabel: { show: true, color: '#94a3b8', fontSize: 10 } },
      yAxis: {
        type: 'category',
        data: categories,
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#64748b', fontSize: 11, fontWeight: 'bold' }
      },
      series: [{
        data: values.map((v, idx) => ({
          value: v,
          itemStyle: { color: this.getGradientByRiskLabel(Object.keys(exposure)[idx], true) }
        })),
        type: 'bar',
        barWidth: '50%',
        itemStyle: { borderRadius: [0, 8, 8, 0] },
        emphasis: {
          itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.1)' }
        }
      }]
    });
  }

  getGradientByRiskLabel(label: string, horizontal: boolean = false): any {
    const l = label.toUpperCase();
    let colors = ['#94a3b8', '#64748b']; // Default Slate

    if (l.includes('CRITICAL') || l.includes('CRITIQUE')) colors = ['#fca5a5', '#ef4444']; // Red
    else if (l.includes('HIGH') || l.includes('ÉLEVÉ')) colors = ['#fdba74', '#f97316'];   // Orange
    else if (l.includes('MEDIUM') || l.includes('MOYEN')) colors = ['#93c5fd', '#3b82f6']; // Blue
    else if (l.includes('LOW') || l.includes('FAIBLE')) colors = ['#a7f3d0', '#10b981'];   // Green

    return new echarts.graphic.LinearGradient(
      horizontal ? 0 : 0,
      horizontal ? 0 : 0,
      horizontal ? 1 : 0,
      horizontal ? 0 : 1,
      [
        { offset: 0, color: colors[0] },
        { offset: 1, color: colors[1] }
      ]
    );
  }

  getRiskLabelFrench(risk: string): string {
    const labels: Record<string, string> = {
      'VERY_LOW': 'Très Faible',
      'LOW': 'Faible',
      'MEDIUM': 'Moyen',
      'HIGH': 'Élevé',
      'CRITICAL': 'Critique'
    };
    return labels[risk] || risk;
  }

  getRiskColor(risk: string): string {
    switch (risk) {
      case 'CRITICAL': return '#fb7185'; // Red
      case 'HIGH': return '#fb923c';     // Orange
      case 'MEDIUM': return '#60a5fa';   // Blue
      case 'LOW': return '#34d399';      // Green
      default: return '#94a3b8';
    }
  }

  formatPercent(val: number): string {
    return (val * 100).toFixed(1) + '%';
  }

  getMetricClass(val: number): string {
    if (val >= 0.9) return 'text-emerald-600';
    if (val >= 0.7) return 'text-blue-600';
    return 'text-amber-600';
  }

  getScenarioLabel(scenario: string): string {
    const labels: Record<string, string> = {
      'mixed': 'Mixte (Standard)',
      'high_amount': 'Montants Élevés',
      'geo_anomaly': 'Anomalies Géographiques',
      'high_frequency': 'Haute Fréquence',
      'sophisticated': 'Fraude Sophistiquée'
    };
    return labels[scenario] || scenario;
  }

  updateFraudRatio(event: any): void {
    this.fraudRatio.set(parseFloat(event.target.value));
  }

  updateTransactionCount(event: any): void {
    this.transactionCount.set(parseInt(event.target.value));
  }

  getSliderBackground(value: number, min: number, max: number): string {
    const percentage = ((value - min) / (max - min)) * 100;
    return `linear-gradient(to right, #1e40af 0%, #1e40af ${percentage}%, #f1f5f9 ${percentage}%, #f1f5f9 100%)`;
  }

  nextPage(): void {
    if (this.currentPage() < this.totalPages()) {
      this.currentPage.update((p: number) => p + 1);
    }
  }

  prevPage(): void {
    if (this.currentPage() > 1) {
      this.currentPage.update((p: number) => p - 1);
    }
  }

  goToPage(p: number): void {
    this.currentPage.set(p);
  }

  setTab(tab: string): void {
    this.activeTab.set(tab);
    if (tab === 'performance') {
      setTimeout(() => this.initCharts(), 100);
    }
  }

  getPeriodLabel(hours: number): string {
    if (hours < 24) return `${hours} heures`;
    if (hours === 24) return '24 heures';
    return `${Math.round(hours / 24)} jours`;
  }

  private scrollTo(id: string): void {
    const element = document.querySelector(id);
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }
}