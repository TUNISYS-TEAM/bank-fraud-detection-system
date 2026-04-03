import { Component, OnInit, inject, signal, computed, OnDestroy } from '@angular/core';
import { DatasetService, Dataset, ApiResponse, DatasetType, DatasetStatus } from '../../../../core/services/dataset.service';
import { AuthStore } from '../../../../core/store/auth.store';
import { KpiCard } from '../../../../shared/cards/cards.component';
import { forkJoin, of, Subscription } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { StatsService } from '../../../../core/services/stats.service';

@Component({
  selector: 'app-datasets',
  templateUrl: './datasets.component.html',
  styleUrl: './datasets.component.css'
})
export class DatasetsComponent implements OnInit, OnDestroy {
  private datasetService = inject(DatasetService);
  private statsService = inject(StatsService);
  authStore = inject(AuthStore);
  private statsSubscription?: Subscription;
  public datasetType = DatasetType;

  kpiCards = signal<KpiCard[]>([
    {
      label: 'Datasets Stockés',
      value: '...',
      icon: 'database',
      color: '#a855f7'
    },
    {
      label: 'Espace Utilisé',
      value: '...',
      icon: 'file-text',
      color: '#22c55e'
    },
    {
      label: 'Dernier Import',
      value: '...',
      icon: 'calendar',
      color: '#f59e0b'
    }
  ]);

  // States using signals
  datasets = signal<Dataset[]>([]);
  loading = signal(false);
  currentPage = signal(0);
  pageSize = 10;
  totalElements = signal(0);
  totalPages = signal(0);
  searchQuery = signal('');
  selectedType = signal<DatasetType | null>(null);
  selectedVisibility = signal<boolean | null>(null);

  datasetTypes = computed(() => {
    if (this.authStore.isAdmin()) {
      return [DatasetType.CLIENTS, DatasetType.ACCOUNTS, DatasetType.TRANSACTIONS_VAL];
    }
    if (this.authStore.isManager()) {
      return [DatasetType.TRANSACTIONS_TRAIN];
    }
    return Object.values(DatasetType).filter(type => type !== DatasetType.TRANSACTIONS_TRAIN_ENRICHED);
  });

  // Modals state
  showCreateModal = signal(false);
  showViewModal = signal(false);
  showUpdateModal = signal(false);
  showDeleteModal = signal(false);
  selectedDataset = signal<Dataset | null>(null);

  // Computed values
  isLoading = computed(() => this.loading());
  hasDatasets = computed(() => this.datasets().length > 0);

  displayRange = computed(() => {
    const start = this.currentPage() * this.pageSize + 1;
    const end = Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());
    return { start, end };
  });

  ngOnInit(): void {
    this.loadDatasets();
    this.loadStats();

    this.statsSubscription = this.statsService.refreshStats$.subscribe(() => {
      this.loadStats();
    });
  }

  ngOnDestroy(): void {
    if (this.statsSubscription) {
      this.statsSubscription.unsubscribe();
    }
  }

  loadStats(): void {
    forkJoin({
      count: this.datasetService.count().pipe(catchError(() => of(0))),
      size: this.datasetService.getTotalSize().pipe(catchError(() => of(0))),
      latestDate: this.datasetService.getLatestImportDate().pipe(catchError(() => of(null)))
    }).subscribe(({ count, size, latestDate }) => {
      this.kpiCards.update(cards => {
        const newCards = [...cards];
        newCards[0].value = count;
        newCards[1].value = (size ? size.toFixed(2) : '0') + ' Mo';
        newCards[2].value = latestDate ? this.formatDate(latestDate) : 'Aucun';
        return newCards;
      });
    });
  }

  loadDatasets(): void {
    this.loading.set(true);
    this.datasetService.list({
      q: this.searchQuery() || undefined,
      type: this.selectedType() || undefined,
      visibility: this.selectedVisibility() !== null ? this.selectedVisibility()! : undefined,
      page: this.currentPage(),
      size: this.pageSize
    }).subscribe({
      next: (response: ApiResponse<Dataset>) => {
        let content = response.content;
        if (this.authStore.isAdmin()) {
          content = content.filter(
            d => d.type !== DatasetType.TRANSACTIONS_TRAIN &&
              d.type !== DatasetType.TRANSACTIONS_TRAIN_ENRICHED
          );
        }
        this.datasets.set(content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
      },
      error: (error: any) => {
        console.error('Error loading datasets:', error);
        this.loading.set(false);
      }
    });
  }

  onSearch(event: any): void {
    const value = event.target.value;
    this.searchQuery.set(value);
    this.currentPage.set(0);
    this.loadDatasets();
  }

  onTypeChange(event: any): void {
    const value = event.target.value;
    this.selectedType.set(value === 'ALL' ? null : value as DatasetType);
    this.currentPage.set(0);
    this.loadDatasets();
  }

  onVisibilityChange(event: any): void {
    const value = event.target.value;
    if (value === 'ALL') {
      this.selectedVisibility.set(null);
    } else {
      this.selectedVisibility.set(value === 'true');
    }
    this.currentPage.set(0);
    this.loadDatasets();
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
    this.loadDatasets();
  }

  formatDate(date: string): string {
    if (!date) return 'N/A';
    return new Date(date).toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric'
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

  // Helper methods for status
  getStatusLabel(status: DatasetStatus | null): string {
    if (!status) return 'Non traité';
    switch (status) {
      case DatasetStatus.PROCESSED: return 'Traité';
      case DatasetStatus.CLEANED: return 'Nettoyé';
      default: return status;
    }
  }

  getStatusClass(status: DatasetStatus | null): string {
    if (!status) return 'bg-gray-100 text-gray-600 border-gray-200';
    switch (status) {
      case DatasetStatus.PROCESSED: return 'bg-blue-50 text-blue-700 border-blue-200';
      case DatasetStatus.CLEANED: return 'bg-purple-50 text-purple-700 border-purple-200';
      default: return 'bg-gray-100 text-gray-600 border-gray-200';
    }
  }

  // Helper methods for visibility
  getVisibilityLabel(visible: boolean): string {
    return visible ? 'Visible' : 'Masqué';
  }

  getVisibilityClass(visible: boolean): string {
    return visible
      ? 'bg-green-50 text-green-700 border-green-200'
      : 'bg-gray-100 text-gray-600 border-gray-200';
  }

  // Modals actions
  openCreateModal(): void {
    this.showCreateModal.set(true);
  }

  closeCreateModal(): void {
    this.showCreateModal.set(false);
  }

  onDatasetUploaded(): void {
    this.statsService.notifyStatsChanged();
    this.loadDatasets();
    this.closeCreateModal();
  }

  openViewModal(dataset: Dataset): void {
    this.selectedDataset.set(dataset);
    this.showViewModal.set(true);
  }

  closeViewModal(): void {
    this.showViewModal.set(false);
    setTimeout(() => this.selectedDataset.set(null), 300);
  }

  openDeleteModal(dataset: Dataset): void {
    this.selectedDataset.set(dataset);
    this.showDeleteModal.set(true);
  }

  closeDeleteModal(): void {
    this.showDeleteModal.set(false);
    setTimeout(() => this.selectedDataset.set(null), 300);
  }

  onDatasetDeleted(): void {
    this.statsService.notifyStatsChanged();
    this.loadDatasets();
    this.closeDeleteModal();
  }

  openUpdateModal(dataset: Dataset): void {
    this.selectedDataset.set(dataset);
    this.showUpdateModal.set(true);
  }

  closeUpdateModal(): void {
    this.showUpdateModal.set(false);
    setTimeout(() => this.selectedDataset.set(null), 300);
  }

  onDatasetUpdated(): void {
    this.statsService.notifyStatsChanged();
    this.loadDatasets();
    this.closeUpdateModal();
  }

  getPageNumbers(): number[] {
    const total = this.totalPages();
    const current = this.currentPage() + 1;
    const delta = 2;
    const range: number[] = [];
    const rangeWithDots: number[] = [];
    let l: number = 0;

    for (let i = 1; i <= total; i++) {
      if (i === 1 || i === total || (i >= current - delta && i <= current + delta)) {
        range.push(i);
      }
    }

    range.forEach((i) => {
      if (l) {
        if (i - l === 2) {
          rangeWithDots.push(l + 1);
        } else if (i - l !== 1) {
          rangeWithDots.push(-1);
        }
      }
      rangeWithDots.push(i);
      l = i;
    });

    return rangeWithDots;
  }
}