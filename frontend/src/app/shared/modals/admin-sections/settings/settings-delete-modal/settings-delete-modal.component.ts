import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-modal-delete',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './settings-delete-modal.component.html',
  styleUrl: './settings-delete-modal.component.css'
})
export class ModalDeleteComponent {
  @Input() isVisible = false;
  @Input() isSaving = false;
  @Output() confirm = new EventEmitter<void>();
  @Output() cancel = new EventEmitter<void>();

  onConfirm() {
    this.confirm.emit();
  }

  onCancel() {
    this.cancel.emit();
  }
}