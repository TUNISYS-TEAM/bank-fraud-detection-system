import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FeatherModule } from 'angular-feather';
import { FraudAlertResponse } from '../../../../core/models/fraud-alert.model';

@Component({
  selector: 'app-alert-details-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, FeatherModule],
  templateUrl: './alert-details-modal.component.html',
  styleUrl: './alert-details-modal.component.css'
})
export class AlertDetailsModalComponent {
  @Input() isOpen = false;
  @Input() alert: FraudAlertResponse | null = null;
  @Input() hideAiAnalysis = false;
  @Output() close = new EventEmitter<void>();
  @Output() action = new EventEmitter<{id: number, type: string, comment: string}>();

  comment = signal('');

  onClose(): void {
    this.close.emit();
  }

  onAction(type: string): void {
    if (this.alert) {
      this.action.emit({
        id: this.alert.id,
        type: type,
        comment: this.comment()
      });
    }
  }

  translateRisk(risk: string | undefined): string {
    if (!risk) return 'N/A';
    switch (risk.toUpperCase()) {
      case 'CRITICAL': return 'Critique';
      case 'HIGH': return 'Élevé';
      case 'MEDIUM': return 'Moyen';
      case 'LOW': return 'Faible';
      default: return risk;
    }
  }

  translateAlertType(type: string | undefined): string {
    if (!type) return 'N/A';
    switch (type.toUpperCase()) {
      case 'PATTERN': return 'Schéma de Fraude';
      case 'LOCATION_ANOMALY': return 'Anomalie de Localisation';
      case 'VELOCITY': return 'Vélocité Inhabituelle';
      case 'AMOUNT_ANOMALY': return 'Montant atypique';
      default: return type;
    }
  }

  getRiskBadgeClass(risk: string | undefined): string {
    if (!risk) return 'bg-gray-100/50 text-gray-500';
    switch (risk.toUpperCase()) {
      case 'CRITICAL': return 'bg-red-50 text-red-600 border-red-100';
      case 'HIGH': return 'bg-orange-50 text-orange-600 border-orange-100';
      case 'MEDIUM': return 'bg-blue-50 text-blue-600 border-blue-100';
      case 'LOW': return 'bg-emerald-50 text-emerald-600 border-emerald-100';
      default: return 'bg-gray-50 text-gray-500 border-gray-100';
    }
  }

  formatDate(dateStr: string | undefined): string {
    if (!dateStr) return 'N/A';
    return new Date(dateStr).toLocaleString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }
}