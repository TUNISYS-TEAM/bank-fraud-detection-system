import { Component, OnInit, OnDestroy, AfterViewInit, inject, NgZone, signal } from '@angular/core';
import * as echarts from 'echarts';
import { AccountService } from '../../../../../../core/services/account.service';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

@Component({
  selector: 'app-accounts',
  templateUrl: './accounts.component.html',
  styleUrls: ['./accounts.component.css']
})
export class AccountsComponent implements OnInit, OnDestroy, AfterViewInit {
  private accountService = inject(AccountService);
  private ngZone = inject(NgZone);

  private statusChart: any = null;
  private valueChart: any = null;
  private evolutionChart: any = null;
  private riskChart: any = null;

  // ── Signals ──────────────────────────────────────────────────────────
  loadingStatusChart = signal(true);
  loadingDataCharts = signal(true);

  ngOnInit(): void {}

  ngAfterViewInit(): void {
    setTimeout(() => {
      this.initCharts();
    }, 300);
  }

  ngOnDestroy(): void {
    this.disposeCharts();
    window.removeEventListener('resize', this.onResize);
  }

  private onResize = () => {
    [this.statusChart, this.valueChart, this.evolutionChart, this.riskChart].forEach(c => c?.resize());
  };

  private disposeCharts(): void {
    [this.statusChart, this.valueChart, this.evolutionChart, this.riskChart].forEach(c => c?.dispose());
  }

  private initCharts(): void {
    this.ngZone.runOutsideAngular(() => {
      this.initStatusChart();
      this.initDataAggregationCharts();
    });
    window.addEventListener('resize', this.onResize);
  }

  // ─── Chart 1: État des Portefeuilles (Direct Count API) ────────────────────
  private initStatusChart(): void {
    const chartDom = document.getElementById('accountStatusChart');
    if (!chartDom) return;

    this.statusChart = echarts.init(chartDom);
    forkJoin({
      total: this.accountService.getTotalCount().pipe(catchError(() => of(0))),
      active: this.accountService.getActiveCount().pipe(catchError(() => of(0)))
    }).subscribe(({ total, active }) => {
      const inactive = total - active;
      this.loadingStatusChart.set(false);
      this.statusChart.setOption({
        tooltip: { trigger: 'item', formatter: '{b}: <strong>{c} ({d}%)</strong>' },
        legend: { bottom: '0%', left: 'center', icon: 'circle', textStyle: { color: '#6b7280' } },
        series: [{
          name: 'État', type: 'pie', radius: ['50%', '75%'], avoidLabelOverlap: false,
          itemStyle: { borderRadius: 12, borderColor: '#fff', borderWidth: 4 },
          label: { show: false, position: 'center' },
          emphasis: { label: { show: true, fontSize: 18, fontWeight: '900', formatter: '{d}%', color: '#111827' } },
          data: [
            { value: active, name: 'Actifs', itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#A7F3D0' }, { offset: 1, color: '#34D399' }]) } },
            { value: inactive, name: 'Inactifs', itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#FECACA' }, { offset: 1, color: '#F87171' }]) } }
          ]
        }]
      });
    });
  }

  // ─── Shared Aggregation Fetching ───────────────────────────────────────────
  private initDataAggregationCharts(): void {
    const valueDom = document.getElementById('accountValueChart');
    const evolDom = document.getElementById('accountEvolutionChart');
    const riskDom = document.getElementById('accountRiskChart');
    if (!valueDom || !evolDom || !riskDom) return;

    this.valueChart = echarts.init(valueDom);
    this.evolutionChart = echarts.init(evolDom);
    this.riskChart = echarts.init(riskDom);

    [this.valueChart, this.evolutionChart, this.riskChart].forEach(c => c.showLoading({ text: 'Chargement...', color: '#6366f1' }));

    forkJoin({
      volumeDist: this.accountService.getVolumeByType().pipe(catchError(() => of({}))),
      riskDist: this.accountService.getRiskDistribution().pipe(catchError(() => of({}))),
      evolDist: this.accountService.getCreationEvolution().pipe(catchError(() => of({})))
    }).subscribe({
      next: ({ volumeDist, riskDist, evolDist }) => {
        this.loadingDataCharts.set(false);
        this.renderValueChart(volumeDist);
        this.renderRiskChart(riskDist);
        this.renderEvolutionChart(evolDist);
        
        [this.valueChart, this.evolutionChart, this.riskChart].forEach(c => c.hideLoading());
      },
      error: () => {
        this.loadingDataCharts.set(false);
        [this.valueChart, this.evolutionChart, this.riskChart].forEach(c => c.hideLoading());
      }
    });
  }

  private renderValueChart(sums: Record<string, number>): void {
    const labelsMap: Record<string, string> = { 'COURANT': 'Courant', 'EPARGNE': 'Épargne', 'DEVISE': 'Devise', 'BLOQUE': 'Bloquée' };
    const categories = Object.keys(labelsMap);
    
    this.valueChart.setOption({
      grid: { left: '3%', right: '8%', bottom: '3%', top: '10%', containLabel: true },
      xAxis: { type: 'value', axisLine: { show: false }, splitLine: { lineStyle: { type: 'dashed', color: '#f3f4f6' } }, axisLabel: { formatter: (v: number) => (v / 1000).toLocaleString() + 'k TND' } },
      yAxis: { type: 'category', data: categories.map(k => labelsMap[k]), axisLabel: { fontWeight: '500' } },
      tooltip: { trigger: 'axis', formatter: (p: any) => `<div class="p-1"><span class="text-xs font-bold uppercase">${p[0].name}</span><br/><span class="text-lg font-black text-indigo-600">${Math.round(p[0].value).toLocaleString()} TND</span></div>` },
      series: [{ name: 'Volume', type: 'bar', data: categories.map(k => sums[k] || 0), itemStyle: { color: new echarts.graphic.LinearGradient(1, 0, 0, 0, [{ offset: 0, color: '#C7D2FE' }, { offset: 1, color: '#818CF8' }]), borderRadius: [0, 8, 8, 0] }, barWidth: '40%' }]
    });
  }

  private renderRiskChart(risks: Record<string, number>): void {
    this.riskChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: <strong>{d}%</strong>' },
      legend: { bottom: '0%', left: 'center', icon: 'circle' },
      series: [{
        name: 'Risque', type: 'pie', roseType: 'area', radius: ['20%', '70%'], center: ['50%', '45%'],
        itemStyle: { borderRadius: 8, borderColor: '#fff' },
        data: [
          { value: risks['LOW'] || risks['low'] || 0, name: 'Risque Faible', itemStyle: { color: '#6EE7B7' } },
          { value: risks['MEDIUM'] || risks['medium'] || 0, name: 'Risque Moyen', itemStyle: { color: '#FCD34D' } },
          { value: risks['HIGH'] || risks['high'] || 0, name: 'Risque Élevé', itemStyle: { color: '#FCA5A5' } }
        ]
      }]
    });
  }

  private renderEvolutionChart(yearCount: Record<string, number>): void {
    const sortedYears = Object.keys(yearCount).sort();
    const counts = sortedYears.map(y => yearCount[y]);

    this.evolutionChart.setOption({
      grid: { left: '3%', right: '4%', bottom: '5%', top: '10%', containLabel: true },
      tooltip: { trigger: 'axis', formatter: (p: any) => `${p[0].name} : <strong>${p[0].value} nouveaux comptes</strong>` },
      xAxis: { type: 'category', data: sortedYears, axisLine: { lineStyle: { color: '#e5e7eb' } } },
      yAxis: { type: 'value', axisLine: { show: false }, splitLine: { lineStyle: { type: 'dashed', color: '#f3f4f6' } } },
      series: [{
        data: counts, type: 'line', smooth: true, symbolSize: 8, itemStyle: { color: '#F59E0B' }, lineStyle: { width: 4 },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(245, 158, 11, 0.2)' }, { offset: 1, color: 'rgba(245, 158, 11, 0)' }]) }
      }]
    });
  }
}