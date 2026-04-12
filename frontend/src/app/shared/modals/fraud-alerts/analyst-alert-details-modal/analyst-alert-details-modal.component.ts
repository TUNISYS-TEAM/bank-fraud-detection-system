import { Component, Output, EventEmitter, input } from '@angular/core';
import { FraudAlertResponse } from '../../../../core/models/fraud-alert.model';
import { AnalystInfo } from '../../../../core/services/fraud-alert.service';
import { ANALYST_COLORS } from '../../../../features/dashboard/pages/alerts/alerts.component';

import { CommonModule, NgClass, DatePipe } from '@angular/common';
import { FeatherModule } from 'angular-feather';

@Component({
  selector: 'app-analyst-alert-details-modal',
  standalone: true,
  imports: [CommonModule, FeatherModule, NgClass, DatePipe],
  templateUrl: './analyst-alert-details-modal.component.html',
  styleUrl: './analyst-alert-details-modal.component.css'
})
export class AnalystAlertDetailsModalComponent {

  analyst = input<AnalystInfo | null>(null);
  alertsForAnalyst = input<FraudAlertResponse[]>([]);
  analysts = input<AnalystInfo[]>([]);
  analystAlertCounts = input<Record<number, number>>({});
  openDropdownUuid = input<string | null>(null);

  @Output() close = new EventEmitter<void>();
  @Output() toggleDropdown = new EventEmitter<string>();
  @Output() reassign = new EventEmitter<{ alertUuid: string; newAnalystId: number }>();
  @Output() unassignAll = new EventEmitter<void>();
  @Output() unassign = new EventEmitter<string>();

  readonly ANALYST_COLORS = ANALYST_COLORS;

  getAnalystColorIndex(analyst: AnalystInfo): number {
    return this.analysts().indexOf(analyst);
  }

  getRiskBadgeClass(riskLevel: string): string {
    switch (riskLevel) {
      case 'CRITICAL': return 'risk-badge-critical';
      case 'HIGH':     return 'risk-badge-high';
      case 'MEDIUM':   return 'risk-badge-medium';
      case 'LOW':      return 'risk-badge-low';
      default:         return 'risk-badge-default';
    }
  }

  getRiskIcon(riskLevel: string): string {
    switch (riskLevel) {
      case 'CRITICAL': return 'alert-octagon';
      case 'HIGH':     return 'alert-triangle';
      case 'MEDIUM':   return 'info';
      case 'LOW':      return 'check-circle';
      default:         return 'circle';
    }
  }

  translateRisk(riskLevel: string): string {
    const map: Record<string, string> = {
      CRITICAL: 'Critique', HIGH: 'Élevé', MEDIUM: 'Moyen', LOW: 'Faible'
    };
    return map[riskLevel] || riskLevel;
  }

  canReassign(): boolean {
    return this.analysts().length > 1;
  }

  translateStatus(status: string): string {
    const map: Record<string, string> = {
      PENDING: 'En attente',
      IN_PROGRESS: 'En cours',
      CONFIRMED: 'CONFIRMÉE',
      FALSE_POSITIVE: 'FAUX POSITIF',
      VALIDATED: 'VALIDÉE',
      REJECTED: 'REJETÉE'
    };
    return map[status] || status;
  }

  getStatusBadgeClass(status: string): string {
    switch (status) {
      case 'PENDING': return 'bg-amber-50 text-amber-600 border-amber-100';
      case 'IN_PROGRESS': return 'bg-blue-50 text-blue-600 border-blue-100';
      case 'CONFIRMED': return 'bg-emerald-100/70 text-emerald-800 border-emerald-200';
      case 'VALIDATED': return 'bg-emerald-100/70 text-emerald-800 border-emerald-200';
      case 'FALSE_POSITIVE': return 'bg-slate-100/80 text-slate-700 border-slate-200';
      case 'REJECTED': return 'bg-slate-100/80 text-slate-700 border-slate-200';
      default: return 'bg-gray-50 text-gray-600 border-gray-100';
    }
  }
}