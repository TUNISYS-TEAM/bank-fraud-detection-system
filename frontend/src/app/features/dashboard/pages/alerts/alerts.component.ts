import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FraudAlertService } from '../../../../core/services/fraud-alert.service';
import { FraudAlertResponse, AlertStatsResponse } from '../../../../core/models/fraud-alert.model';
import { KpiCard } from '../../../../shared/cards/cards.component';

@Component({
  selector: 'app-alerts',
  templateUrl: './alerts.component.html',
  styleUrl: './alerts.component.css'
})
export class AlertsComponent implements OnInit {
  private alertService = inject(FraudAlertService);

  // Data signals
  private allAlerts = signal<FraudAlertResponse[]>([]);
  stats = signal<AlertStatsResponse | null>(null);
  isLoading = signal(false);

  // Pagination signals
  currentPage = signal(0);
  pageSize = signal(10);
  totalElements = signal(0);
  totalPages = signal(0);

  // Filter signals
  searchQuery = signal('');
  statusFilter = signal<string>('all');
  riskLevelFilter = signal<string>('all');

  // Computed: filtered alerts based on search query
  alerts = computed(() => {
    const query = this.stripAccents(this.searchQuery().trim().toLowerCase());
    const all = this.allAlerts();
    if (!query) return all;

    return all.filter(alert => {
      const txnId = String(alert.transactionId ?? '').toLowerCase();
      const txnIdWithPrefix = 'txn-' + txnId;
      const alertType = this.stripAccents((alert.alertType ?? '').toLowerCase());
      const translatedType = this.stripAccents(this.translateAlertType(alert.alertType).toLowerCase());
      const amount = String(alert.transactionAmount ?? '').toLowerCase();
      const uuid = (alert.alertUuid ?? '').toLowerCase();
      const status = this.stripAccents((alert.status ?? '').toLowerCase());
      const translatedStatus = this.stripAccents(this.translateStatus(alert.status).toLowerCase());

      return txnId.includes(query)
        || txnIdWithPrefix.includes(query)
        || alertType.includes(query)
        || translatedType.includes(query)
        || amount.includes(query)
        || uuid.includes(query)
        || status.includes(query)
        || translatedStatus.includes(query);
    });
  });

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

  handleAlertAction(event: {id: number, type: string, comment: string}): void {
    console.log('Action performed:', event);
    // TODO: Connect to backend service
    this.closeDetails();
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
      undefined,
      this.currentPage(),
      this.pageSize()
    ).subscribe({
      next: (response) => {
        this.allAlerts.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
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

  onSearchChange(query: string): void {
    this.searchQuery.set(query);
    this.currentPage.set(0);
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
      case 'RESOLVED': return 'bg-emerald-100 text-emerald-700 border-emerald-200';
      case 'FALSE_POSITIVE': return 'bg-gray-100 text-gray-700 border-gray-200';
      default: return 'bg-gray-50 text-gray-600 border-gray-100';
    }
  }

  translateStatus(status: string): string {
    switch (status?.toUpperCase()) {
      case 'PENDING': return 'En Attente';
      case 'REVIEWED': return 'En Cours';
      case 'RESOLVED': return 'Résolue';
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
    const data = this.alerts();
    if (!data || data.length === 0) {
      alert('Aucune alerte à exporter');
      return;
    }

    // Headers with semicolon delimiter for French Excel
    const headers = ['ID Transaction;Date de Création;Type d\'Alerte;Montant;Score de Fraude;Statut'];
    
    // Rows
    const rows = data.map(alert => [
      `TXN-${alert.transactionId ?? ''}`,
      new Date(alert.createdAt).toLocaleString('fr-FR'),
      this.translateAlertType(alert.alertType),
      `${(alert.transactionAmount ?? 0).toFixed(2)}`,
      `${(alert.fraudScore * 100).toFixed(0)}%`,
      this.translateStatus(alert.status)
    ].join(';'));

    const csvContent = [headers, ...rows].join('\n');

    // Create and trigger download
    const blob = new Blob(['\ufeff' + csvContent], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    const url = URL.createObjectURL(blob);
    
    link.setAttribute('href', url);
    link.setAttribute('download', `alertes_fraude_${new Date().toISOString().split('T')[0]}.csv`);
    link.style.visibility = 'hidden';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }
}