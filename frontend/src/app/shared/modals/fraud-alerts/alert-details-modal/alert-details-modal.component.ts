import { Component, EventEmitter, Input, Output, signal, OnChanges, SimpleChanges, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FeatherModule } from 'angular-feather';
import { FraudAlertResponse } from '../../../../core/models/fraud-alert.model';
import { AuthService } from '../../../../core/services/auth.service';

@Component({
  selector: 'app-alert-details-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, FeatherModule],
  templateUrl: './alert-details-modal.component.html',
  styleUrl: './alert-details-modal.component.css'
})
export class AlertDetailsModalComponent implements OnChanges {
  @Input() isOpen = false;
  @Input() alert: FraudAlertResponse | null = null;
  @Input() hideAiAnalysis = false;
  @Input() hideActions = false;
  @Input() isProcessing = false;
  @Output() close = new EventEmitter<void>();
  @Output() action = new EventEmitter<{uuid: string, type: string, comment: string}>();

  private authService = inject(AuthService);
  isAdmin = computed(() => this.authService.getCurrentUser()?.role === 'ADMIN');
  isManager = computed(() => this.authService.getCurrentUser()?.role === 'MANAGER');
  isAnalyst = computed(() => this.authService.getCurrentUser()?.role === 'ANALYST');
  
  comment = signal('');

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['alert'] && this.alert) {
      this.comment.set(this.alert.reviewComment || '');
    }
    if (changes['isOpen'] && this.isOpen && this.alert) {
       this.comment.set(this.alert.reviewComment || '');
    }
  }

  onClose(): void {
    this.close.emit();
  }

  onAction(type: string): void {
    if (this.alert && !this.isProcessing) {
      this.action.emit({
        uuid: this.alert.alertUuid,
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

  getStatusBadgeClass(status: string | undefined): string {
    if (!status) return 'bg-gray-100 text-gray-500 border-gray-200';
    switch (status.toUpperCase()) {
      case 'PENDING': return 'bg-amber-50 text-amber-600 border-amber-100';
      case 'REVIEWED': return 'bg-blue-50 text-blue-600 border-blue-100';
      case 'CONFIRMED': return 'bg-emerald-50 text-emerald-600 border-emerald-100';
      case 'FALSE_POSITIVE': return 'bg-slate-50 text-slate-600 border-slate-200';
      default: return 'bg-gray-50 text-gray-500 border-gray-100';
    }
  }

  translateStatus(status: string | undefined): string {
    if (!status) return 'Inconnu';
    switch (status.toUpperCase()) {
      case 'PENDING': return 'En Attente';
      case 'REVIEWED': return 'En Cours';
      case 'CONFIRMED': return 'Confirmée';
      case 'FALSE_POSITIVE': return 'Faux Positif';
      default: return status;
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

  isObject(val: any): boolean {
    return typeof val === 'object' && val !== null && !Array.isArray(val);
  }

  asStructuredReason(reason: any): any {
    if (reason && reason.summary && Array.isArray(reason.contributions)) {
      return reason;
    }
    return null;
  }

  getFraudReasons(reasons: any): {key: string, value: any}[] {
    if (!reasons) return [];
    if (this.asStructuredReason(reasons)) return []; // Handle separately in template
    if (this.isObject(reasons)) {
      return Object.keys(reasons).map(key => ({
        key: key,
        value: reasons[key]
      }));
    }
    return [{key: 'Raison', value: reasons}];
  }

  getImpactColor(points: number): string {
    if (points >= 50) return 'text-indigo-600 bg-indigo-50 border-indigo-200';
    if (points >= 20) return 'text-indigo-500 bg-indigo-50/60 border-indigo-100';
    return 'text-indigo-400 bg-indigo-50/40 border-indigo-50';
  }

  fixEncoding(str: string | undefined): string {
    if (!str) return '';
    try {
      // Common hack to fix UTF-8 mangled as ISO-8859-1
      return decodeURIComponent(escape(str));
    } catch (e) {
      // Fallback if it's not actually mangled or double encoded
      return str;
    }
  }

  sanitizeReason(reason: any): any {
    if (!reason) return null;
    if (typeof reason === 'string') return this.fixEncoding(reason);
    
    // Deep clone and fix encoding in all string fields
    const sanitized = JSON.parse(JSON.stringify(reason));
    
    if (sanitized.summary) {
      sanitized.summary = this.fixEncoding(sanitized.summary);
    }
    
    if (Array.isArray(sanitized.contributions)) {
      sanitized.contributions = sanitized.contributions.map((c: any) => ({
        ...c,
        description: this.fixEncoding(c.description),
        factor_display: this.fixEncoding(c.factor_display)
      }));
    }
    
    return sanitized;
  }

  getSummaryParts(summary: string | undefined): {level: string, text: string} {
    if (!summary) return {level: '', text: ''};
    const parts = summary.split(':');
    if (parts.length > 1) {
      return {
        level: this.translateRisk(parts[0].trim()),
        text: parts.slice(1).join(':').trim()
      };
    }
    return {level: '', text: summary};
  }
}