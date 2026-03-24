import { Component, OnInit, OnDestroy, AfterViewInit, inject, NgZone, signal } from '@angular/core';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import * as echarts from 'echarts';
import { ClientService, ClientDetail } from '../../../../../../core/services/client.service';
import { AccountService } from '../../../../../../core/services/account.service';

@Component({
  selector: 'app-clients',
  templateUrl: './clients.component.html',
  styleUrls: ['./clients.component.css']
})
export class ClientsComponent implements OnInit, OnDestroy, AfterViewInit {
  private clientService = inject(ClientService);
  private accountService = inject(AccountService);
  private ngZone = inject(NgZone);

  private accountChart: any = null;
  private growthChart: any = null;
  private ageChart: any = null;
  private riskChart: any = null;

  // ── Signals ──────────────────────────────────────────────────────────
  loadingAccountChart = signal(true);
  loadingDetailCharts = signal(true);

  ngOnInit(): void { }

  ngAfterViewInit(): void {
    setTimeout(() => {
      this.ngZone.runOutsideAngular(() => {
        this.initGrowthChart();
        this.initAccountDistribution();
        this.initStatsCharts();
      });
      window.addEventListener('resize', this.onResize);
    }, 300);
  }

  ngOnDestroy(): void {
    this.disposeCharts();
    window.removeEventListener('resize', this.onResize);
  }

  private onResize = () => {
    this.accountChart?.resize();
    this.growthChart?.resize();
    this.ageChart?.resize();
    this.riskChart?.resize();
  };

  private disposeCharts(): void {
    [this.accountChart, this.growthChart, this.ageChart, this.riskChart]
      .forEach(c => c?.dispose());
  }

  // ─── Chart 1: Répartition par Portefeuille ──────────────────────────────────
  private initAccountDistribution(): void {
    const chartDom = document.getElementById('accountDistributionChart');
    if (!chartDom) return;

    this.accountChart = echarts.init(chartDom);
    this.accountChart.showLoading({
      text: 'Chargement...', color: '#6366f1',
      textColor: '#6b7280', maskColor: 'rgba(255,255,255,0.9)'
    });

    this.clientService.getAccountDistribution().subscribe({
      next: (dist) => {
        this.loadingAccountChart.set(false);
        this.accountChart.hideLoading();
        this.accountChart.setOption({
          tooltip: {
            trigger: 'item',
            formatter: '{b}: <span style="font-weight:bold">{c} Clients ({d}%)</span>',
            backgroundColor: 'rgba(255,255,255,0.96)',
            borderColor: '#e5e7eb', borderWidth: 1,
            textStyle: { color: '#1f2937' }, padding: [8, 12],
            shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.1)'
          },
          legend: {
            bottom: '0%', left: 'center', icon: 'circle', itemGap: 24,
            textStyle: { color: '#6b7280', fontSize: 12 }
          },
          series: [{
            name: 'Nombre de Comptes',
            type: 'pie',
            radius: ['50%', '75%'],
            avoidLabelOverlap: false,
            itemStyle: { borderRadius: 12, borderColor: '#fff', borderWidth: 4 },
            label: { show: false, position: 'center' },
            emphasis: {
              label: { show: true, fontSize: 18, fontWeight: '900',
                formatter: '{d}%', color: '#111827', lineHeight: 24 }
            },
            labelLine: { show: false },
              data: [
                { value: dist['1 compte'] || 0,   name: '1 compte',   itemStyle: { color: '#C7D2FE' } },
                { value: dist['2 comptes'] || 0,  name: '2 comptes',  itemStyle: { color: '#818CF8' } },
                { value: dist['3+ comptes'] || 0, name: '3+ comptes', itemStyle: { color: '#6366F1' } }
              ]
          }]
        });
      },
      error: () => {
        this.loadingAccountChart.set(false);
        this.accountChart.hideLoading();
      }
    });
  }

  // ─── Chart 2: Acquisition Clients (données simulées) ────────────────────────
  private initGrowthChart(): void {
    const chartDom = document.getElementById('clientGrowthChart');
    if (!chartDom) return;

    this.growthChart = echarts.init(chartDom);
    const months = ['Oct', 'Nov', 'Déc', 'Jan', 'Fév', 'Mar'];
    const data   = [45, 52, 48, 65, 78, 92];

    this.growthChart.setOption({
      grid: { left: '3%', right: '4%', bottom: '10%', top: '10%', containLabel: true },
      xAxis: {
        type: 'category', boundaryGap: false, data: months,
        axisLine: { lineStyle: { color: '#e5e7eb' } },
        axisLabel: { color: '#6b7280' }
      },
      yAxis: {
        type: 'value', axisLine: { show: false },
        axisLabel: { color: '#6b7280' },
        splitLine: { lineStyle: { type: 'dashed', color: '#f3f4f6' } }
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'line', lineStyle: { color: '#10b981', width: 2 } }
      },
      series: [{
        data, type: 'line', smooth: true, symbolSize: 8,
        itemStyle: { color: '#10b981' },
        lineStyle: { width: 4 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(16,185,129,0.2)' },
            { offset: 1, color: 'rgba(16,185,129,0)' }
          ])
        }
      }]
    });
  }

  // ─── Charts 3 & 4: Aggregation SQL sans échantillonnage ─────────────────────
  private initStatsCharts(): void {
    const ageDom  = document.getElementById('ageDistributionChart');
    const riskDom = document.getElementById('riskLevelChart');
    if (!ageDom || !riskDom) return;

    this.ageChart  = echarts.init(ageDom);
    this.riskChart = echarts.init(riskDom);

    const loadingOpts = {
      text: 'Chargement...', color: '#6366f1',
      textColor: '#6b7280', maskColor: 'rgba(255,255,255,0.9)'
    };
    this.ageChart.showLoading(loadingOpts);
    this.riskChart.showLoading(loadingOpts);

    forkJoin({
      ageDist: this.clientService.getAgeDistribution().pipe(catchError(() => of({}))),
      riskDist: this.accountService.getRiskDistribution().pipe(catchError(() => of({})))
    }).subscribe({
      next: ({ ageDist, riskDist }) => {
        this.loadingDetailCharts.set(false);
        this.ageChart.hideLoading();
        this.riskChart.hideLoading();
        this.renderAgeChart(ageDist);
        this.renderRiskChart(riskDist);
      },
      error: () => {
        this.loadingDetailCharts.set(false);
        this.ageChart.hideLoading();
        this.riskChart.hideLoading();
      }
    });
  }

  // ─── Chart 3: Répartition par Tranche d'Âge ─────────────────────────────────
  private renderAgeChart(bands: Record<string, number>): void {
    const categories = ['Moins de 30 ans', '30 – 45 ans', '46 – 60 ans', 'Plus de 60 ans'];
    const colors = ['#EDE9FE', '#DDD6FE', '#A78BFA', '#8B5CF6'];

    this.ageChart.setOption({
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: any[]) =>
          `${params[0].name} : <strong>${params[0].value} clients</strong>`,
        backgroundColor: 'rgba(255,255,255,0.96)',
        borderColor: '#e5e7eb', borderWidth: 1,
        textStyle: { color: '#1f2937' }, padding: [8, 12]
      },
      grid: { left: '3%', right: '10%', bottom: '8%', top: '8%', containLabel: true },
      xAxis: {
        type: 'value', axisLine: { show: false },
        axisLabel: { color: '#6b7280' },
        splitLine: { lineStyle: { type: 'dashed', color: '#f3f4f6' } }
      },
      yAxis: {
        type: 'category',
        data: categories,
        axisLine: { lineStyle: { color: '#e5e7eb' } },
        axisLabel: { color: '#374151', fontSize: 12 }
      },
      series: [{
        type: 'bar',
        data: categories.map((cat, i) => ({
          value: bands[cat] || 0,
          itemStyle: { color: colors[i], borderRadius: [0, 8, 8, 0] }
        })),
        barMaxWidth: 40,
        label: { show: true, position: 'right', color: '#6b7280', fontSize: 12 }
      }]
    });
  }

  // ─── Chart 4: Nightingale Rose Chart — Profil de Risque ──────────────────────
  private renderRiskChart(riskCount: Record<string, number>): void {
    const data = [
      {
        value: riskCount['LOW'] || riskCount['low'] || 0,
        name: 'Risque Faible',
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#A7F3D0' }, { offset: 1, color: '#34D399' }
          ])
        }
      },
      {
        value: riskCount['MEDIUM'] || riskCount['medium'] || 0,
        name: 'Risque Moyen',
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#FBBF24' }, { offset: 1, color: '#FCD34D' }
          ])
        }
      },
      {
        value: riskCount['HIGH'] || riskCount['high'] || 0,
        name: 'Risque Élevé',
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#F87171' }, { offset: 1, color: '#FCA5A5' }
          ])
        }
      }
    ];

    this.riskChart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        formatter: (p: any) =>
          `<strong>${p.name}</strong><br/>Comptes&nbsp;: <strong>${p.value}</strong><br/>Part&nbsp;: <strong>${p.percent}%</strong>`,
        backgroundColor: 'rgba(255,255,255,0.97)',
        borderColor: '#e5e7eb', borderWidth: 1,
        textStyle: { color: '#1f2937', fontSize: 13 },
        padding: [10, 14], shadowBlur: 12, shadowColor: 'rgba(0,0,0,0.08)'
      },
      legend: {
        bottom: '0%', left: 'center', icon: 'circle', itemGap: 28,
        textStyle: { color: '#6b7280', fontSize: 12 }
      },
      series: [{
        name: 'Risque',
        type: 'pie',
        roseType: 'area',
        radius: ['15%', '75%'],
        center: ['50%', '47%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 3 },
        label: {
          show: true,
          formatter: '{b}\n{d}%',
          color: '#374151',
          fontSize: 12,
          fontWeight: '600'
        },
        emphasis: {
          itemStyle: { shadowBlur: 20, shadowColor: 'rgba(0,0,0,0.15)' },
          label: { fontSize: 14, fontWeight: '900' }
        },
        data: data
      }]
    });
  }
}