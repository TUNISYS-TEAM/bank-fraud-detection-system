import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../../environments/environment';
import { AuthStore } from '../../../../../../core/store/auth.store';

export interface LogEntry {
  id: string;
  sessionId: string;
  action: string;
  actionType: string;
  userName?: string;
  userEmail?: string;
  actionTimestamp: string;
  level: 'Info' | 'Succès' | 'Attention' | 'Erreur';
}

export interface LogsResponse {
  content: LogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export type ExportFormat = 'csv' | 'json' | 'pdf';
export type ExportDateRange = 'all' | 'custom';

@Component({
  selector: 'app-logs',
  templateUrl: './logs.component.html',
  styleUrl: './logs.component.css'
})
export class LogsComponent implements OnInit {
  private http = inject(HttpClient);
  authStore = inject(AuthStore);

  allLogs = signal<LogEntry[]>([]);
  loading = signal(false);

  currentPage = signal(0);
  pageSize = 200;

  searchTerm = signal('');
  levelFilter = signal<string>('');
  dateFrom = signal<string>('');
  dateTo = signal<string>('');

  frontendPage = signal(0);
  frontendPageSize = 5;

  showExportModal = signal(false);
  isLoading = computed(() => this.loading());

  filteredLogs = computed(() => {
    let logs = this.allLogs();
    const search = this.searchTerm().toLowerCase().trim();
    const level = this.levelFilter();
    const from = this.dateFrom();
    const to = this.dateTo();

    if (search) {
      logs = logs.filter(log =>
        log.action?.toLowerCase().includes(search) ||
        log.actionType?.toLowerCase().includes(search) ||
        log.userName?.toLowerCase().includes(search) ||
        log.userEmail?.toLowerCase().includes(search)
      );
    }

    if (level) {
      logs = logs.filter(log => log.level === level);
    }

    if (from) {
      const fromDate = new Date(from);
      logs = logs.filter(log => new Date(log.actionTimestamp) >= fromDate);
    }

    if (to) {
      const toDate = new Date(to);
      toDate.setHours(23, 59, 59);
      logs = logs.filter(log => new Date(log.actionTimestamp) <= toDate);
    }

    return logs;
  });

  paginatedLogs = computed(() => {
    const start = this.frontendPage() * this.frontendPageSize;
    return this.filteredLogs().slice(start, start + this.frontendPageSize);
  });

  filteredTotalPages = computed(() =>
    Math.ceil(this.filteredLogs().length / this.frontendPageSize)
  );

  filteredTotalElements = computed(() => this.filteredLogs().length);

  displayRange = computed(() => {
    const start = this.frontendPage() * this.frontendPageSize + 1;
    const end = Math.min(
      (this.frontendPage() + 1) * this.frontendPageSize,
      this.filteredTotalElements()
    );
    return { start, end };
  });

  levelOptions = [
    { value: '', label: 'Tous les niveaux' },
    { value: 'Info', label: 'Info', class: 'bg-blue-50 text-blue-700 border-blue-200' },
    { value: 'Succès', label: 'Succès', class: 'bg-green-50 text-green-700 border-green-200' },
    { value: 'Attention', label: 'Attention', class: 'bg-amber-50 text-amber-700 border-amber-200' },
    { value: 'Erreur', label: 'Erreur', class: 'bg-red-50 text-red-700 border-red-200' }
  ];

  ngOnInit(): void {
    this.loadLogs();
  }

  loadLogs(): void {
    this.loading.set(true);
    const url = `${environment.apiUrl}/logs?page=${this.currentPage()}&size=${this.pageSize}`;

    this.http.get<LogsResponse>(url).subscribe({
      next: (response) => {
        this.allLogs.set(response.content);
        this.frontendPage.set(0);
        this.loading.set(false);
      },
      error: (error) => {
        console.error('Error loading logs:', error);
        this.loading.set(false);
      }
    });
  }

  onPageChange(page: number): void {
    this.frontendPage.set(page);
  }

  applySearch(): void {
    this.frontendPage.set(0);
  }

  updateSearchTerm(value: string): void {
    this.searchTerm.set(value);
    this.frontendPage.set(0);
  }

  updateLevelFilter(value: string): void {
    this.levelFilter.set(value);
    this.frontendPage.set(0);
  }

  updateDateFrom(value: string): void {
    this.dateFrom.set(value);
    this.frontendPage.set(0);
  }

  updateDateTo(value: string): void {
    this.dateTo.set(value);
    this.frontendPage.set(0);
  }

  resetFilters(): void {
    this.searchTerm.set('');
    this.levelFilter.set('');
    this.dateFrom.set('');
    this.dateTo.set('');
    this.frontendPage.set(0);
  }

  openExportModal(): void {
    this.showExportModal.set(true);
  }

  closeExportModal(): void {
    this.showExportModal.set(false);
  }

  onExportLogs(event: { format: ExportFormat; dateRange: ExportDateRange; customFrom?: string; customTo?: string }): void {
    const url = `${environment.apiUrl}/logs/export?format=${event.format}`;

    this.http.get(url, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        const fileName = `logs_export_${new Date().toISOString().split('T')[0]}.${event.format}`;
        const link = document.createElement('a');
        link.href = window.URL.createObjectURL(blob);
        link.download = fileName;
        link.click();
        this.closeExportModal();
      },
      error: (error) => {
        console.error('Error exporting logs:', error);
      }
    });
  }

  getLevelClass(level: string): string {
    const option = this.levelOptions.find(opt => opt.value === level);
    return option?.class || 'bg-gray-50 text-gray-700 border-gray-200';
  }

  getLevelIcon(level: string): string {
    switch (level) {
      case 'Succès': return 'M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z';
      case 'Attention': return 'M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z';
      case 'Erreur': return 'M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z';
      default: return 'M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z';
    }
  }

  formatDate(date: string): string {
    return new Date(date).toLocaleString('fr-FR', {
      day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit'
    });
  }

  getPageNumbers(): number[] {
    const total = this.filteredTotalPages();
    const current = this.frontendPage() + 1;
    const delta = 2;
    const range: number[] = [];
    const rangeWithDots: number[] = [];
    let l!: number;

    for (let i = 1; i <= total; i++) {
      if (i === 1 || i === total || (i >= current - delta && i <= current + delta)) {
        range.push(i);
      }
    }

    range.forEach((i) => {
      if (l) {
        if (i - l === 2) rangeWithDots.push(l + 1);
        else if (i - l !== 1) rangeWithDots.push(-1);
      }
      rangeWithDots.push(i);
      l = i;
    });

    return rangeWithDots;
  }

  getUserInitials(log: LogEntry): string {
    if (log.userName) {
      const names = log.userName.split(' ');
      if (names.length >= 2) return (names[0][0] + names[names.length - 1][0]).toUpperCase();
      return log.userName.substring(0, 2).toUpperCase();
    }
    if (log.userEmail) return log.userEmail.substring(0, 2).toUpperCase();
    return 'SY';
  }
}