import { Component, EventEmitter, Output, inject, signal, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../environments/environment';

export type ExportFormat = 'csv' | 'json' | 'pdf';
export type ExportDateRange = 'all' | 'custom';

@Component({
  selector: 'app-log-export-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './log-export-modal.component.html',
  styleUrls: ['./log-export-modal.component.css']
})
export class LogExportModalComponent {
  @Input() searchTerm: string = '';
  @Input() levelFilter: string = '';
  @Input() dateFrom: string = '';
  @Input() dateTo: string = '';
  
  @Output() close = new EventEmitter<void>();
  @Output() export = new EventEmitter<{
    format: ExportFormat;
    dateRange: ExportDateRange;
    customFrom?: string;
    customTo?: string;
  }>();

  // Signals
  isVisible = signal(true);
  exportFormat = signal<ExportFormat>('csv');
  exportDateRange = signal<ExportDateRange>('all');
  exportCustomFrom = signal<string>('');
  exportCustomTo = signal<string>('');

  closeModal(): void {
    this.isVisible.set(false);
    setTimeout(() => this.close.emit(), 300);
  }

  onExport(): void {
    this.export.emit({
      format: this.exportFormat(),
      dateRange: this.exportDateRange(),
      customFrom: this.exportDateRange() === 'custom' ? this.exportCustomFrom() : undefined,
      customTo: this.exportDateRange() === 'custom' ? this.exportCustomTo() : undefined
    });
    this.closeModal();
  }

  hasActiveFilters(): boolean {
    return !!(this.searchTerm || this.levelFilter || this.dateFrom || this.dateTo);
  }
}