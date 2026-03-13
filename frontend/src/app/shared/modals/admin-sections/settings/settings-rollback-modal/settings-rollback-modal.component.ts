import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

export interface ArchivePreviewData {
  type: string;
  rowCount: number;
  startAt: string;
  endAt: string;
}

@Component({
  selector: 'app-modal-view-rollback',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './settings-rollback-modal.component.html',
  styleUrl: './settings-rollback-modal.component.css'
})
export class ModalViewRollbackComponent {
  @Input() isVisible = false;
  @Input() data: ArchivePreviewData | null = null;
  @Output() close = new EventEmitter<void>();

  onClose() {
    this.close.emit();
  }
}