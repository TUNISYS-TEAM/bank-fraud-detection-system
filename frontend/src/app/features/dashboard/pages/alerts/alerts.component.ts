import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FraudAlertService } from '../../../../core/services/fraud-alert.service';
import { FraudAlertResponse, AlertStatsResponse } from '../../../../core/models/fraud-alert.model';
import { KpiCard } from '../../../../shared/cards/cards.component';
import { ToastrService } from 'ngx-toastr';
import { AuthStore } from '../../../../core/store/auth.store';

@Component({
  selector: 'app-alerts',
  templateUrl: './alerts.component.html',
  styleUrl: './alerts.component.css'
})
export class AlertsComponent implements OnInit {
  private alertService = inject(FraudAlertService);
  private toastr = inject(ToastrService);
  private authStore = inject(AuthStore);

  userRole = computed(() => this.authStore.user()?.role);

  // Data signals
  private allAlerts = signal<FraudAlertResponse[]>([]);
  stats = signal<AlertStatsResponse | null>(null);
  isLoading = signal(false);

  // Pagination signals
  currentPage = signal(0);
  pageSize = signal(5);

  // Filter signals
  searchQuery = signal('');
  statusFilter = signal<string>('all');
  riskLevelFilter = signal<string>('all');

  // 1. Computed: applied global filter on full dataset
  filteredAlerts = computed(() => {
    const query = this.stripAccents(this.searchQuery().trim().toLowerCase());
    const all = this.allAlerts();
    
    if (!query) return all;

    return all.filter(alert => {
      const txnId = String(alert.transactionId ?? '').toLowerCase();
      const txnIdWithPrefix = 'txn-' + txnId;
      const type = this.stripAccents((alert.alertType ?? '').toLowerCase());
      const translatedType = this.stripAccents(this.translateAlertType(alert.alertType).toLowerCase());
      const amount = String(alert.transactionAmount ?? '').toLowerCase();
      const status = this.stripAccents(this.translateStatus(alert.status).toLowerCase());

      return txnId.includes(query)
        || txnIdWithPrefix.includes(query)
        || type.includes(query)
        || translatedType.includes(query)
        || amount.includes(query)
        || status.includes(query);
    });
  });

  // 2. Computed: stats and pagination metadata (Automatic)
  totalElements = computed(() => this.filteredAlerts().length);
  totalPages = computed(() => Math.ceil(this.totalElements() / this.pageSize()));

  // 3. Computed: Final slice displayed in the table
  alerts = computed(() => {
    const start = this.currentPage() * this.pageSize();
    const end = start + this.pageSize();
    return this.filteredAlerts().slice(start, end);
  });

  // Helper for Export
  filteredAlertsForExport = computed(() => this.filteredAlerts());

  // Strip accents for accent-insensitive search
  private stripAccents(str: string): string {
    return str.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  }

  // KPI Cards computed signals
  totalAlertsCard = computed((): KpiCard => ({
    label: 'Total Alertes',
    value: this.stats()?.total || 0,
    icon: 'bell',
    color: '#4f46e5',
    description: 'Alertes détectées'
  }));

  pendingAlertsCard = computed((): KpiCard => ({
    label: 'En Attente',
    value: this.stats()?.pending || 0,
    icon: 'clock',
    color: '#f59e0b',
    description: 'En attente de revue'
  }));

  reviewedAlertsCard = computed((): KpiCard => ({
    label: 'En Cours',
    value: this.stats()?.reviewed || 0,
    icon: 'activity',
    color: '#3b82f6',
    description: 'En cours de traitement'
  }));

  criticalAlertsCard = computed((): KpiCard => ({
    label: 'Critiques',
    value: this.stats()?.critical || 0,
    icon: 'alert-triangle',
    color: '#ef4444',
    description: 'Niveau de risque élevé'
  }));

  // Modal signals
  selectedAlert = signal<FraudAlertResponse | null>(null);
  isDetailsOpen = signal(false);
  isModalProcessing = signal(false);

  ngOnInit(): void {
    this.loadStats();
    this.loadAlerts();
  }

  openDetails(alert: FraudAlertResponse): void {
    this.selectedAlert.set(alert);
    this.isDetailsOpen.set(true);
  }

  closeDetails(): void {
    this.isDetailsOpen.set(false);
    this.selectedAlert.set(null);
  }

  handleAlertAction(event: {uuid: string, type: string, comment: string}): void {
    let status = 'PENDING';
    let actionTaken = '';

    if (event.type === 'VALIDE_FRAUDE') {
      status = 'CONFIRMED';
      actionTaken = 'CONFIRMED_FRAUD';
    } else if (event.type === 'REJETTE_ALERTE') {
      status = 'FALSE_POSITIVE';
      actionTaken = 'DISMISSED';
    } else if (event.type === 'MARK_AS_REVIEWED') {
      status = 'REVIEWED';
      actionTaken = 'REVIEWED';
    } else {
      return; // Action not handled here
    }

    const alertId = this.selectedAlert()?.id;
    this.isModalProcessing.set(true);
    this.alertService.updateAlertStatus(event.uuid, {
      status: status,
      reviewComment: event.comment,
      actionTaken: actionTaken
    }).subscribe({
      next: () => {
        let message = '';
        
        if (event.type === 'VALIDE_FRAUDE') {
          message = `L'alerte d'id #${alertId} a été validée comme fraude`;
        } else if (event.type === 'REJETTE_ALERTE') {
          message = `L'alerte d'id #${alertId} a été rejetée`;
        } else if (event.type === 'MARK_AS_REVIEWED') {
          message = `L'alerte d'id #${alertId} a été marquée comme en cours d'analyse`;
        }
        
        this.toastr.success(message, 'Succès');
        this.isModalProcessing.set(false);
        this.closeDetails();
        this.loadAlerts();
        this.loadStats();
      },
      error: (err) => {
        console.error('Error updating alert status', err);
        this.toastr.error('Erreur lors de la mise à jour de l\'alerte', 'Erreur');
        this.isModalProcessing.set(false);
      }
    });
  }

  loadStats(): void {
    this.alertService.getAlertStats().subscribe({
      next: (stats) => {
        console.log('Alert Stats Received:', stats);
        this.stats.set(stats);
      },
      error: (err) => console.error('Error loading alert stats', err)
    });
  }

  loadAlerts(): void {
    this.isLoading.set(true);
    this.alertService.getAlerts(
      this.riskLevelFilter(),
      this.statusFilter(),
      undefined,
      undefined, // No server-side search to keep the "old" behavior's flexibility
      0,
      2000 // Large size to have all alerts for filtering/export
    ).subscribe({
      next: (response) => {
        this.allAlerts.set(response.content);
        this.isLoading.set(false);
      },
      error: (err) => {
        console.error('Error loading alerts', err);
        this.isLoading.set(false);
      }
    });
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
    this.loadAlerts();
  }

  getAlertDisplayRange() {
    const start = this.currentPage() * this.pageSize() + 1;
    const end = Math.min((this.currentPage() + 1) * this.pageSize(), this.totalElements());
    return { start, end };
  }

  onSearchChange(query: string): void {
    this.searchQuery.set(query);
    this.currentPage.set(0);
    this.loadAlerts(); // Re-trigger load from server with search query
  }

  setRiskFilter(risk: string): void {
    this.riskLevelFilter.set(risk);
    this.currentPage.set(0);
    this.loadAlerts();
  }

  setStatusFilter(status: string): void {
    this.statusFilter.set(status);
    this.currentPage.set(0);
    this.loadAlerts();
  }

  getStatusBadgeClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'PENDING': return 'bg-amber-100 text-amber-700 border-amber-200';
      case 'REVIEWED': return 'bg-blue-100 text-blue-700 border-blue-200';
      case 'CONFIRMED': return 'bg-emerald-100 text-emerald-700 border-emerald-200';
      case 'FALSE_POSITIVE': return 'bg-gray-100 text-gray-700 border-gray-200';
      default: return 'bg-gray-50 text-gray-600 border-gray-100';
    }
  }

  translateStatus(status: string): string {
    switch (status?.toUpperCase()) {
      case 'PENDING': return 'En Attente';
      case 'REVIEWED': return 'En Cours';
      case 'CONFIRMED': return 'Confirmée';
      case 'FALSE_POSITIVE': return 'Faux Positif';
      default: return status;
    }
  }

  translateRisk(risk: string): string {
    switch (risk?.toUpperCase()) {
      case 'CRITICAL': return 'Critique';
      case 'HIGH': return 'Élevé';
      case 'MEDIUM': return 'Moyen';
      case 'LOW': return 'Faible';
      default: return risk;
    }
  }

  translateAlertType(type: string): string {
    switch (type?.toUpperCase()) {
      case 'PATTERN': return 'Schéma de Fraude';
      case 'LOCATION_ANOMALY': return 'Anomalie de Localisation';
      case 'VELOCITY': return 'Vélocité Inhabituelle';
      case 'AMOUNT_ANOMALY': return 'Montant Anormal';
      default: return type;
    }
  }

  getRiskBadgeClass(risk: string): string {
    switch (risk?.toUpperCase()) {
      case 'CRITICAL': return 'risk-badge-critical';
      case 'HIGH': return 'risk-badge-high';
      case 'MEDIUM': return 'risk-badge-medium';
      case 'LOW': return 'risk-badge-low';
      default: return 'risk-badge-default';
    }
  }

  getRiskIcon(risk: string): string {
    switch (risk?.toUpperCase()) {
      case 'CRITICAL': return 'alert-octagon';
      case 'HIGH': return 'alert-triangle';
      case 'MEDIUM': return 'info';
      case 'LOW': return 'check-circle';
      default: return 'shield';
    }
  }

  exportToCSV(): void {
    const data = this.filteredAlertsForExport();
    if (!data || data.length === 0) {
      this.toastr.warning('Aucune alerte à exporter', 'Export');
      return;
    }

        // Headers with semicolon delimiter for French Excel
        const headers = ['ID Transaction;Nom Client;Date de Création;Type d\'Alerte;Montant;Niveau de Risque;Score de Fraude;Statut;Commentaire'];
        
        // Rows
        const rows = data.map((alert: FraudAlertResponse) => [
          `TXN-${alert.transactionId ?? ''}`,
          alert.clientName || `CLIENT-${alert.userId}`,
          new Date(alert.createdAt).toLocaleString('fr-FR'),
          this.translateAlertType(alert.alertType),
          `${(alert.transactionAmount ?? 0).toFixed(2)} ${alert.transactionCurrency || 'TND'}`,
          this.translateRisk(alert.riskLevel),
          `${(alert.fraudScore * 100).toFixed(0)}%`,
          this.translateStatus(alert.status),
          (alert.reviewComment || '').replace(/;/g, ',').replace(/\n/g, ' ') // Escape semicolons and newlines
        ].join(';'));

        const csvContent = [headers, ...rows].join('\r\n');

        // Create and trigger download with BOM for Excel UTF-8 support
        const blob = new Blob(['\ufeff' + csvContent], { type: 'text/csv;charset=utf-8;' });
        const link = document.createElement('a');
        const url = URL.createObjectURL(blob);
        
        link.setAttribute('href', url);
        link.setAttribute('download', `Rapport_Alertes_Fraude_${new Date().toISOString().split('T')[0]}.csv`);
        link.style.visibility = 'hidden';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        
        this.toastr.success(`${data.length} alertes exportées avec succès`, 'Export Terminé', {
          positionClass: 'toast-bottom-right',
          timeOut: 5000,
          progressBar: true
        });
  }
}