import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { environment } from '../../../../../environments/environment';
import { DatasetService, Dataset, ApiResponse, DatasetType } from '../../../../core/services/dataset.service';
import { AuthStore } from '../../../../core/store/auth.store';
import { KpiCard } from '../../../../shared/cards/cards.component';

@Component({
  selector: 'app-train-data',
  templateUrl: './train-data.component.html',
  styleUrls: ['./train-data.component.css']
})
export class TrainDataComponent implements OnInit {
  private http = inject(HttpClient);
  private datasetService = inject(DatasetService);
  authStore = inject(AuthStore);
  public datasetType = DatasetType;

  // KPI Cards
  kpiCards = signal<KpiCard[]>([
    { label: 'Datasets Stockés', value: '...', icon: 'database', color: '#4f46e5' },
    { label: 'Espace Total', value: '...', icon: 'hard-drive', color: '#22c55e' },
    { label: 'Total Lignes', value: '...', icon: 'list', color: '#a855f7' },
    { label: 'Dernier Import', value: '...', icon: 'clock', color: '#f97316' }
  ]);

  // Dataset table state
  datasets = signal<Dataset[]>([]);
  loading = signal(false);
  currentPage = signal(0);
  pageSize = 10;
  totalElements = signal(0);
  totalPages = signal(0);
  searchQuery = signal('');

  // Modals
  showCreateModal = signal(false);
  showViewModal = signal(false);
  showUpdateModal = signal(false);
  showDeleteModal = signal(false);
  selectedDataset = signal<Dataset | null>(null);

  // Computed
  isLoading = computed(() => this.loading());
  hasDatasets = computed(() => this.datasets().length > 0);
  displayRange = computed(() => {
    const start = this.currentPage() * this.pageSize + 1;
    const end = Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());
    return { start, end };
  });

  ngOnInit(): void {
    this.loadStats();
    this.loadDatasets();
  }

  // ── KPIs ──────────────────────────────────────────────────────────────
  private loadStats(): void {
    // Get count for TRANSACTIONS_TRAIN datasets
    const count$ = this.http.get<number>(
      `${environment.apiUrl}/datasets/count`,
      { params: { types: 'TRANSACTIONS_TRAIN' } }
    ).pipe(catchError(() => of(0)));

    // Get total size for TRANSACTIONS_TRAIN datasets
    const size$ = this.http.get<number>(
      `${environment.apiUrl}/datasets/total-size`,
      { params: { types: 'TRANSACTIONS_TRAIN' } }
    ).pipe(catchError(() => of(0)));

    forkJoin({ count: count$, size: size$ }).subscribe({
      next: (stats) => {
        this.kpiCards.update(cards => {
          const c = [...cards];
          c[0].value = this.formatNumber(stats.count ?? 0);
          c[1].value = (stats.size ? Number(stats.size).toFixed(2) : '0') + ' MB';
          return c;
        });
      }
    });
  }

  private updateExtraKpis(): void {
    const ds = this.datasets();
    const totalRows = ds.reduce((sum, d) => sum + (d.rowCount || 0), 0);
    const latestDate = ds.length > 0
      ? ds.map(d => d.importedAt).sort().reverse()[0]
      : null;

    this.kpiCards.update(cards => {
      const c = [...cards];
      c[2].value = this.formatNumber(totalRows);
      c[3].value = latestDate ? this.formatDate(latestDate) : 'Aucun';
      return c;
    });
  }

  // ── Datasets ──────────────────────────────────────────────────────────
  loadDatasets(): void {
    this.loading.set(true);
    
    // Call the list method with proper parameters
    this.datasetService.list({
      q: this.searchQuery() || undefined,
      type: DatasetType.TRANSACTIONS_TRAIN,
      page: this.currentPage(),
      size: this.pageSize
    }).subscribe({
      next: (response: ApiResponse<Dataset>) => {
        this.datasets.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
        this.updateExtraKpis();
      },
      error: (error) => {
        console.error('Error loading datasets:', error);
        this.loading.set(false);
      }
    });
  }

  onSearch(event: any): void {
    this.searchQuery.set(event.target.value);
    this.currentPage.set(0);
    this.loadDatasets();
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
    this.loadDatasets();
  }

  // ── Formatters ────────────────────────────────────────────────────────
  formatDate(date: string): string {
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
    if (!mb && mb !== 0) return 'N/A';
    if (mb < 1024) return `${mb.toFixed(2)} MB`;
    return `${(mb / 1024).toFixed(2)} GB`;
  }

  formatNumber(n: number): string {
    if (!n && n !== 0) return '0';
    return new Intl.NumberFormat('fr-FR').format(n);
  }

  // ── Modals ────────────────────────────────────────────────────────────
  openCreateModal(): void { 
    this.showCreateModal.set(true); 
  }
  
  closeCreateModal(): void { 
    this.showCreateModal.set(false); 
  }
  
  onDatasetUploaded(): void { 
    this.loadStats(); 
    this.loadDatasets(); 
    this.closeCreateModal(); 
  }

  openViewModal(ds: Dataset): void { 
    this.selectedDataset.set(ds); 
    this.showViewModal.set(true); 
  }
  
  closeViewModal(): void { 
    this.showViewModal.set(false); 
    setTimeout(() => this.selectedDataset.set(null), 300); 
  }

  openDeleteModal(ds: Dataset): void { 
    this.selectedDataset.set(ds); 
    this.showDeleteModal.set(true); 
  }
  
  closeDeleteModal(): void { 
    this.showDeleteModal.set(false); 
    setTimeout(() => this.selectedDataset.set(null), 300); 
  }
  
  onDatasetDeleted(): void { 
    this.loadStats(); 
    this.loadDatasets(); 
    this.closeDeleteModal(); 
  }

  openUpdateModal(ds: Dataset): void { 
    this.selectedDataset.set(ds); 
    this.showUpdateModal.set(true); 
  }
  
  closeUpdateModal(): void { 
    this.showUpdateModal.set(false); 
    setTimeout(() => this.selectedDataset.set(null), 300); 
  }
  
  onDatasetUpdated(): void { 
    this.loadDatasets(); 
    this.closeUpdateModal(); 
  }

  // ── Pagination ────────────────────────────────────────────────────────
  getPageNumbers(): number[] {
    const total = this.totalPages();
    const current = this.currentPage() + 1;
    const delta = 2;
    const range: number[] = [];
    const result: number[] = [];
    let l: number = 0;

    if (total <= 1) return [1];

    for (let i = 1; i <= total; i++) {
      if (i === 1 || i === total || (i >= current - delta && i <= current + delta)) {
        range.push(i);
      }
    }

    range.forEach((i) => {
      if (l) {
        if (i - l === 2) result.push(l + 1);
        else if (i - l !== 1) result.push(-1);
      }
      result.push(i);
      l = i;
    });

    return result;
  }
}