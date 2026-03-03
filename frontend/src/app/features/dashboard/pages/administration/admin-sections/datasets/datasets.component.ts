import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { DatasetService, Dataset, DatasetStatus, ApiResponse } from '../../../../../../core/services/dataset.service';
import { AuthStore } from '../../../../../../core/store/auth.store';
import { DatasetCreateModalComponent } from '../../../../../../shared/modals/admin-sections/datasets/dataset-create-modal/dataset-create-modal.component';
import { DatasetViewModalComponent } from '../../../../../../shared/modals/admin-sections/datasets/dataset-view-modal/dataset-view-modal.component';
import { DatasetUpdateModalComponent } from '../../../../../../shared/modals/admin-sections/datasets/dataset-update-modal/dataset-update-modal.component';
import { DatasetDeleteModalComponent } from '../../../../../../shared/modals/admin-sections/datasets/dataset-delete-modal/dataset-delete-modal.component';

@Component({
  selector: 'app-datasets',
  templateUrl: './datasets.component.html',
  styleUrl: './datasets.component.css'
})
export class DatasetsComponent implements OnInit {
  private datasetService = inject(DatasetService);
  authStore = inject(AuthStore);

  // States using signals
  datasets = signal<Dataset[]>([]);
  loading = signal(false);
  currentPage = signal(0);
  pageSize = 10;
  totalElements = signal(0);
  totalPages = signal(0);
  searchQuery = signal('');

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
  }

  loadDatasets(): void {
    this.loading.set(true);
    this.datasetService.list({
      q: this.searchQuery(),
      page: this.currentPage(),
      size: this.pageSize
    }).subscribe({
      next: (response: ApiResponse<Dataset>) => {
        this.datasets.set(response.content);
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
    if (mb < 1024) return `${mb.toFixed(2)} MB`;
    return `${(mb / 1024).toFixed(2)} GB`;
  }

  formatNumber(n: number): string {
    return new Intl.NumberFormat('fr-FR').format(n);
  }

  // Modals actions
  openCreateModal(): void {
    this.showCreateModal.set(true);
  }

  closeCreateModal(): void {
    this.showCreateModal.set(false);
  }

  onDatasetUploaded(): void {
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
    this.loadDatasets();
    this.closeUpdateModal();
  }

  getPageNumbers(): number[] {
    const total = this.totalPages();
    const current = this.currentPage() + 1;
    const delta = 2;
    const range: number[] = [];
    const rangeWithDots: number[] = [];
    let l: number;

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