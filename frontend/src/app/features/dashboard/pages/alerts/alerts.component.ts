import { Component, OnInit, signal, computed, inject, HostListener } from '@angular/core';
import { FraudAlertService, AnalystInfo } from '../../../../core/services/fraud-alert.service';
import { FraudAlertResponse, AlertStatsResponse } from '../../../../core/models/fraud-alert.model';
import { KpiCard } from '../../../../shared/cards/cards.component';
import { ToastrService } from 'ngx-toastr';
import { AuthStore } from '../../../../core/store/auth.store';

export const ANALYST_COLORS = [
  { bg: '#6366f1', light: '#eef2ff', border: '#c7d2fe', text: '#4338ca' },
  { bg: '#f59e0b', light: '#fffbeb', border: '#fde68a', text: '#b45309' },
  { bg: '#10b981', light: '#ecfdf5', border: '#a7f3d0', text: '#047857' },
  { bg: '#f43f5e', light: '#fff1f2', border: '#fecdd3', text: '#be123c' },
  { bg: '#06b6d4', light: '#ecfeff', border: '#a5f3fc', text: '#0e7490' },
  { bg: '#8b5cf6', light: '#f5f3ff', border: '#ddd6fe', text: '#6d28d9' },
  { bg: '#f97316', light: '#fff7ed', border: '#fed7aa', text: '#c2410c' },
  { bg: '#14b8a6', light: '#f0fdfa', border: '#99f6e4', text: '#0f766e' },
];

@Component({
  selector: 'app-alerts',
  templateUrl: './alerts.component.html',
  styleUrl: './alerts.component.css'
})
export class AlertsComponent implements OnInit {
  protected readonly ANALYST_COLORS = ANALYST_COLORS;
  private alertService = inject(FraudAlertService);
  private toastr = inject(ToastrService);
  private authStore = inject(AuthStore);

  userRole = computed(() => this.authStore.user()?.role);
  isManager = computed(() => this.userRole() === 'MANAGER' || this.userRole() === 'ADMIN');

  // Tabs
  activeTab = signal<'alerts' | 'assignment'>('alerts');

  // Data signals
  allAlerts = signal<FraudAlertResponse[]>([]);
  stats = signal<AlertStatsResponse | null>(null);
  isLoading = signal(false);

  // Analysts (for assignment tab)
  analysts = signal<AnalystInfo[]>([]);
  isLoadingAnalysts = signal(false);

  // Selection state (for assignment)
  selectedAlertUuids = signal<Set<string>>(new Set());
  isAssigning = signal(false);
  unassignedOnly = signal(false);

  // Pagination signals
  currentPage = signal(0);
  pageSize = signal(10);

  // Filter signals
  searchQuery = signal('');
  statusFilter = signal<string>('all');
  riskLevelFilter = signal<string>('all');
  scoreSortDirection = signal<'ASC' | 'DESC' | null>(null);

  // 1. Computed: applied global filter on full dataset
  filteredAlerts = computed(() => {
    const query = this.stripAccents(this.searchQuery().trim().toLowerCase());
    const statusF = this.statusFilter();
    const riskF = this.riskLevelFilter();
    let all = this.allAlerts();

    if (this.unassignedOnly() && this.isManager()) {
      all = all.filter(a => !a.assignedAnalystId);
    }

    // Client-side status filter
    if (statusF && statusF !== 'all') {
      all = all.filter(a => a.status === statusF);
    }

    // Client-side risk level filter
    if (riskF && riskF !== 'all') {
      all = all.filter(a => a.riskLevel === riskF);
    }

    if (!query && !statusF && !riskF && !this.scoreSortDirection()) return all;

    let filtered = all.filter(alert => {
      const txnId = String(alert.transactionId ?? '').toLowerCase();
      const txnIdWithPrefix = 'txn-' + txnId;
      const type = this.stripAccents((alert.alertType ?? '').toLowerCase());
      const translatedType = this.stripAccents(this.translateAlertType(alert.alertType).toLowerCase());
      const amount = String(alert.transactionAmount ?? '').toLowerCase();
      const status = this.stripAccents(this.translateStatus(alert.status).toLowerCase());

      const matchesQuery = !query || (
        txnId.includes(query) ||
        txnIdWithPrefix.includes(query) ||
        type.includes(query) ||
        translatedType.includes(query) ||
        amount.includes(query) ||
        status.includes(query)
      );

      return matchesQuery;
    });

    // Apply sorting
    const sortDir = this.scoreSortDirection();
    if (sortDir) {
      filtered = [...filtered].sort((a, b) => {
        const scoreA = a.fraudScore ?? 0;
        const scoreB = b.fraudScore ?? 0;
        return sortDir === 'DESC' ? scoreB - scoreA : scoreA - scoreB;
      });
    }

    return filtered;
  });

  // 2. Computed: stats and pagination metadata
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

  // Computed: count of unassigned alerts (for manager)
  unassignedCount = computed(() => this.allAlerts().filter(a => !a.assignedAnalystId).length);

  // Computed: per-analyst alert count in current data
  analystAlertCounts = computed(() => {
    const counts: Record<number, number> = {};
    for (const alert of this.allAlerts()) {
      if (alert.assignedAnalystId) {
        counts[alert.assignedAnalystId] = (counts[alert.assignedAnalystId] || 0) + 1;
      }
    }
    return counts;
  });

  currentUserAlerts = computed(() => {
    const u = this.authStore.user();
    const userId = u?.userId;
    if (!userId) return [];
    return this.allAlerts().filter(a => a.assignedAnalystId === userId);
  });

  // Computed: alerts list for the currently managed analyst in the modal
  alertsForSelectedAnalyst = computed(() => {
    const analyst = this.selectedAnalystForManagement();
    if (!analyst) return [];
    const analystId = analyst.id;
    return this.allAlerts().filter(a => {
      const assignedId = a.assignedAnalystId;
      return assignedId != null && String(assignedId) === String(analystId);
    });
  });

  // Helpers for the swimlane template
  getAlertsForAnalyst(analystId: number): FraudAlertResponse[] {
    return this.allAlerts().filter(a => a.assignedAnalystId === analystId);
  }

  // All unassigned (used for selection, counts, etc.)
  getAllUnassignedAlerts(): FraudAlertResponse[] {
    const risk = this.assignRiskFilter();
    return this.allAlerts().filter(a => {
      const isUnassigned = !a.assignedAnalystId;
      const matchesRisk = risk === 'all' || a.riskLevel === risk;
      return isUnassigned && matchesRisk;
    });
  }

  // Assignment tab pagination & filtering
  assignPage = signal(0);
  assignPageSize = signal(10);
  assignRiskFilter = signal<string>('all');

  // Drag and Drop state
  isDragging = signal(false);
  draggedAlertUuid = signal<string | null>(null);
  activeDropAnalystId = signal<number | null>(null);

  // Analyst alerts management
  selectedAnalystForManagement = signal<AnalystInfo | null>(null);
  isAnalystManagementVisible = signal<boolean>(false);
  isReassignDropdownOpen = signal<string | null>(null);
  private pendingSilentRefresh = false;

  // Marquee Selection state
  isMarqueeSelecting = signal(false);
  marqueeBox = signal<{ x1: number, y1: number, x2: number, y2: number } | null>(null);
  private startX = 0;
  private startY = 0;

  assignTotalPages = computed(() => Math.ceil(this.unassignedCount() / this.assignPageSize()));

  // Paginated slice for the table
  getUnassignedAlerts(): FraudAlertResponse[] {
    const all = this.getAllUnassignedAlerts();
    const start = this.assignPage() * this.assignPageSize();
    return all.slice(start, start + this.assignPageSize());
  }

  getAssignDisplayRange() {
    const start = this.assignPage() * this.assignPageSize() + 1;
    const end = Math.min((this.assignPage() + 1) * this.assignPageSize(), this.unassignedCount());
    return { start, end };
  }

  onAssignPageChange(page: number): void {
    this.assignPage.set(page);
  }

  // Analyst Management Methods
  openAnalystManagement(analyst: AnalystInfo): void {
    this.selectedAnalystForManagement.set(analyst);
    this.isAnalystManagementVisible.set(true);
  }

  closeAnalystManagement(): void {
    this.isAnalystManagementVisible.set(false);
    this.selectedAnalystForManagement.set(null);
    this.isReassignDropdownOpen.set(null);
    // Sync with server now that the modal is closed
    this.pendingSilentRefresh = false;
    this.silentRefresh();
  }

  toggleReassignDropdown(alertUuid: string): void {
    if (this.isReassignDropdownOpen() === alertUuid) {
      this.isReassignDropdownOpen.set(null);
    } else {
      this.isReassignDropdownOpen.set(alertUuid);
      // Small timeout to allow the dropdown to render before scrolling
      setTimeout(() => {
        const dropdown = document.querySelector('.reassign-dropdown-container');
        if (dropdown) {
          dropdown.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
      }, 50);
    }
  }


  // Drag and Drop handlers
  onDragStart(event: DragEvent, alertUuid: string): void {
    this.isDragging.set(true);
    this.draggedAlertUuid.set(alertUuid);

    if (event.dataTransfer) {
      event.dataTransfer.setData('text/plain', alertUuid);
      event.dataTransfer.effectAllowed = 'move';

      if (this.isSelected(alertUuid) && this.selectedCount > 1) {
      }
    }
  }

  onDragEnd(): void {
    this.isDragging.set(false);
    this.draggedAlertUuid.set(null);
  }

  onDragOver(event: DragEvent, analystId: number): void {
    event.preventDefault(); 
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
    this.activeDropAnalystId.set(analystId);
  }

  onDragLeave(): void {
    this.activeDropAnalystId.set(null);
  }

  onDrop(event: DragEvent, analystId: number): void {
    event.preventDefault();
    this.activeDropAnalystId.set(null);
    const alertUuid = event.dataTransfer?.getData('text/plain') || this.draggedAlertUuid();

    if (alertUuid) {
      const selection = this.selectedAlertUuids();

      if (selection.size > 0) {
        const uuidsToAssign = new Set(selection);
        uuidsToAssign.add(alertUuid);

        this.performAssignment(Array.from(uuidsToAssign), analystId);
      } else {
        this.performAssignment([alertUuid], analystId);
      }
    }
    this.onDragEnd();
  }

  // Computed: for the donut chart
  donutSegments = computed(() => {
    const total = this.allAlerts().length;
    if (total === 0) return [];
    const unassigned = this.unassignedCount();
    const segments: { label: string; count: number; color: string; percentage: number }[] = [];

    this.analysts().forEach((analyst, idx) => {
      const count = this.analystAlertCounts()[analyst.id] || 0;
      if (count > 0) {
        segments.push({
          label: analyst.name,
          count,
          color: ANALYST_COLORS[idx % ANALYST_COLORS.length].bg,
          percentage: Math.round((count / total) * 100)
        });
      }
    });

    if (unassigned > 0) {
      segments.push({
        label: 'Non assignées',
        count: unassigned,
        color: '#e5e7eb',
        percentage: Math.round((unassigned / total) * 100)
      });
    }
    return segments;
  });

  private readonly CIRC = 282.74;

  getDonutDash(percentage: number): string {
    const dash = (percentage / 100) * this.CIRC;
    return `${dash} ${this.CIRC - dash}`;
  }

  getDonutOffset(segmentIndex: number): number {
    const segs = this.donutSegments();
    let offset = 0;
    for (let i = 0; i < segmentIndex; i++) {
      offset += (segs[i].percentage / 100) * this.CIRC;
    }
    return this.CIRC - offset;
  }

  private stripAccents(str: string): string {
    return str.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  }

  // Analyst color helpers
  getAnalystColor(analystId: number) {
    const idx = this.analysts().findIndex(a => a.id === analystId);
    return idx >= 0 ? ANALYST_COLORS[idx % ANALYST_COLORS.length] : ANALYST_COLORS[0];
  }

  getAnalystColorByIndex(idx: number) {
    return ANALYST_COLORS[idx % ANALYST_COLORS.length];
  }

  // KPI Cards computed signals
  totalAlertsCard = computed((): KpiCard => {
    const isMan = this.isManager();
    const value = isMan ? (this.stats()?.total || 0) : this.currentUserAlerts().length;
    return {
      label: 'Total Alertes',
      value: value,
      icon: 'bell',
      color: '#4f46e5',
      description: isMan ? 'Alertes détectées' : 'Mes alertes assignées'
    };
  });

  pendingAlertsCard = computed((): KpiCard => {
    const isMan = this.isManager();
    const value = isMan ? (this.stats()?.pending || 0) : this.currentUserAlerts().filter(a => a.status === 'PENDING').length;
    return {
      label: 'En Attente',
      value: value,
      icon: 'clock',
      color: '#f59e0b',
      description: 'En attente de revue'
    };
  });

  reviewedAlertsCard = computed((): KpiCard => {
    const isMan = this.isManager();
    const value = isMan ? (this.stats()?.reviewed || 0) : this.currentUserAlerts().filter(a => a.status === 'IN_PROGRESS' || a.status === 'REVIEWED').length;
    return {
      label: 'En Cours',
      value: value,
      icon: 'activity',
      color: '#3b82f6',
      description: 'En cours de traitement'
    };
  });

  criticalAlertsCard = computed((): KpiCard => {
    const isMan = this.isManager();
    const value = isMan ? (this.stats()?.critical || 0) : this.currentUserAlerts().filter(a => a.riskLevel === 'CRITICAL').length;
    return {
      label: 'Critiques',
      value: value,
      icon: 'alert-triangle',
      color: '#ef4444',
      description: 'Niveau de risque élevé'
    };
  });

  // Modal signals
  selectedAlert = signal<FraudAlertResponse | null>(null);
  isDetailsOpen = signal(false);
  isModalProcessing = signal(false);

  ngOnInit(): void {
    this.loadStats();
    this.loadAlerts();
    if (this.isManager()) {
      this.loadAnalysts();
    }
  }

  openDetails(alert: FraudAlertResponse): void {
    this.selectedAlert.set(alert);
    this.isDetailsOpen.set(true);
  }

  closeDetails(): void {
    this.isDetailsOpen.set(false);
    this.selectedAlert.set(null);
  }

  setActiveTab(tab: 'alerts' | 'assignment'): void {
    this.activeTab.set(tab);
    this.selectedAlertUuids.set(new Set());
    if (tab === 'assignment') {
      if (this.allAlerts().length > 0) {
        this.silentRefresh();
      } else {
        this.loadAlerts();
      }
      this.loadAnalysts();
    }
  }

  // ============ SELECTION METHODS ============

  toggleAlertSelection(uuid: string): void {
    const current = new Set(this.selectedAlertUuids());
    if (current.has(uuid)) {
      current.delete(uuid);
    } else {
      current.add(uuid);
    }
    this.selectedAlertUuids.set(current);
  }

  isSelected(uuid: string): boolean {
    return this.selectedAlertUuids().has(uuid);
  }

  get selectedCount(): number {
    return this.selectedAlertUuids().size;
  }

  selectAllUnassigned(): void {
    const unassigned = this.allAlerts()
      .filter(a => !a.assignedAnalystId)
      .map(a => a.alertUuid);
    this.selectedAlertUuids.set(new Set(unassigned));
  }

  clearSelection(): void {
    this.selectedAlertUuids.set(new Set());
  }

  toggleUnassignedFilter(): void {
    this.unassignedOnly.update(v => !v);
    this.currentPage.set(0);
  }

  assignSelectedTo(analystId: number): void {
    const uuids = Array.from(this.selectedAlertUuids());
    if (uuids.length === 0) {
      this.toastr.warning('Sélectionnez des alertes à affecter', 'Affectation');
      return;
    }
    this.performAssignment(uuids, analystId);
  }

  assignSingleAlert(alertUuid: string, analystId: number): void {
    this.performAssignment([alertUuid], analystId);
  }

  reassignAlert(alertUuid: string, newAnalystId: number): void {
    this.isReassignDropdownOpen.set(null);

    const analyst = this.analysts().find(a => a.id === newAnalystId);

    const targetUuid = alertUuid.trim();
    this.allAlerts.update(alerts => [...alerts].map(a =>
      a.alertUuid.trim() === targetUuid
        ? { ...a, assignedAnalystId: newAnalystId, assignedAnalystName: analyst?.name }
        : a
    ));

    this.alertService.assignAlerts([alertUuid], newAnalystId).subscribe({
      next: () => {
        this.toastr.success(`Alerte réaffectée à ${analyst?.name}`, 'Succès');
        if (this.isAnalystManagementVisible()) {
          this.pendingSilentRefresh = true;
        } else {
          setTimeout(() => this.silentRefresh(), 1000);
        }
      },
      error: (err) => {
        console.error('Error reassigning alert:', err);
        this.toastr.error("Erreur lors de la réaffectation", 'Erreur');
        this.silentRefresh(); 
      }
    });
  }

  unassignAllFromAnalyst(analystId: number): void {
    const currentList = this.alertsForSelectedAnalyst().filter(a => a.status === 'PENDING');
    const alertsToUnassign = currentList.map(a => a.alertUuid);

    if (alertsToUnassign.length === 0) {
      this.toastr.info("Aucune alerte en attente à désaffecter", "Info");
      return;
    }

    const uuidsToClear = new Set(alertsToUnassign);
    this.allAlerts.update(alerts => alerts.map(a =>
      uuidsToClear.has(a.alertUuid) ? { ...a, assignedAnalystId: undefined, assignedAnalystName: undefined } : a
    ));

    this.alertService.unassignAlerts(alertsToUnassign).subscribe({
      next: () => {
        this.toastr.success(`${alertsToUnassign.length} alertes désaffectées`, 'Succès');
        if (this.isAnalystManagementVisible()) {
          this.pendingSilentRefresh = true;
        } else {
          setTimeout(() => this.silentRefresh(), 1000);
        }
      },
      error: (err) => {
        console.error('Error unassigning all alerts:', err);
        this.toastr.error("Erreur lors de la désaffectation groupée", 'Erreur');
        this.silentRefresh();
      }
    });
  }

  unassignAlert(alertUuid: string): void {
    if (!alertUuid) return;
    
    const target = this.allAlerts().find(a => a.alertUuid === alertUuid);
    if (target && target.status !== 'PENDING') {
      this.toastr.warning("Impossible de désaffecter une alerte déjà en cours de traitement", "Attention");
      return;
    }

    this.isReassignDropdownOpen.set(null);

    const targetUuid = alertUuid.trim();

    this.allAlerts.update(alerts => alerts.map(a =>
      a.alertUuid.trim() === targetUuid ? { ...a, assignedAnalystId: undefined, assignedAnalystName: undefined } : a
    ));

    this.alertService.unassignAlerts([targetUuid]).subscribe({
      next: () => {
        this.toastr.success('Alerte désaffectée', 'Succès');
        if (this.isAnalystManagementVisible()) {
          this.pendingSilentRefresh = true;
        } else {
          setTimeout(() => this.silentRefresh(), 1000);
        }
      },
      error: (err) => {
        console.error('Error unassigning alert:', err);
        this.toastr.error("Erreur lors de la désaffectation", 'Erreur');
        this.silentRefresh(); // Rollback
      }
    });
  }

  /**
   * Generic assignment method to handle both single and multiple alerts
   */
  private performAssignment(uuids: string[], analystId: number): void {
    if (!uuids || uuids.length === 0) return;

    this.isAssigning.set(true);
    const analyst = this.analysts().find(a => a.id === analystId);

    const uuidSet = new Set(uuids.map(u => u.trim()));
    this.allAlerts.update(alerts => [...alerts].map(a =>
      uuidSet.has(a.alertUuid.trim())
        ? { ...a, assignedAnalystId: analystId, assignedAnalystName: analyst?.name }
        : a
    ));

    this.alertService.assignAlerts(uuids, analystId).subscribe({
      next: () => {
        const count = uuids.length;
        if (count > 1) {
          this.toastr.success(`${count} alertes affectées à ${analyst?.name}`, 'Affectation réussie');
        } else {
          this.toastr.success(`Alerte affectée à ${analyst?.name}`, 'Succès');
        }

        this.isAssigning.set(false);
        this.selectedAlertUuids.set(new Set()); 

        const uuidSet = new Set(uuids);
        this.allAlerts.update(alerts => alerts.map(a =>
          uuidSet.has(a.alertUuid)
            ? { ...a, assignedAnalystId: analystId, assignedAnalystName: analyst?.name }
            : a
        ));

        setTimeout(() => this.silentRefresh(), 1000);
      },
      error: (err) => {
        console.error('Error assigning alerts', err);
        this.toastr.error("Erreur lors de l'affectation", 'Erreur');
        this.isAssigning.set(false);
      }
    });
  }

  // ============ LOAD METHODS ============

  loadAnalysts(): void {
    this.isLoadingAnalysts.set(true);
    this.alertService.getMyAnalysts().subscribe({
      next: (analysts) => {
        this.analysts.set(analysts);
        this.isLoadingAnalysts.set(false);
      },
      error: (err) => {
        console.error('Error loading analysts', err);
        this.isLoadingAnalysts.set(false);
      }
    });
  }

  handleAlertAction(event: { uuid: string, type: string, comment: string }): void {
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
      return;
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
        this.stats.set(stats);
      },
      error: (err) => console.error('Error loading alert stats', err)
    });
  }

  loadAlerts(): void {
    this.isLoading.set(true);
    this.alertService.getAlerts(
      'all',
      'all',
      undefined,
      undefined,
      0,
      2000
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

  private silentRefresh(): void {
    this.alertService.getAlerts(
      'all',
      'all',
      undefined,
      undefined,
      0,
      2000
    ).subscribe({
      next: (response) => this.allAlerts.set(response.content),
      error: (err) => console.error('Silent refresh failed', err)
    });

    this.alertService.getMyAnalysts().subscribe({
      next: (analysts) => this.analysts.set(analysts),
      error: (err) => console.error('Silent analyst refresh failed', err)
    });
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
  }

  getAlertDisplayRange() {
    const start = this.currentPage() * this.pageSize() + 1;
    const end = Math.min((this.currentPage() + 1) * this.pageSize(), this.totalElements());
    return { start, end };
  }

  onSearchChange(query: string): void {
    this.searchQuery.set(query);
    this.currentPage.set(0);
  }

  setRiskFilter(risk: string): void {
    this.riskLevelFilter.set(risk);
    this.currentPage.set(0);
  }

  setStatusFilter(status: string): void {
    this.statusFilter.set(status);
    this.currentPage.set(0);
  }

  toggleScoreSort(): void {
    const current = this.scoreSortDirection();
    if (current === 'DESC') {
      this.scoreSortDirection.set('ASC');
    } else if (current === 'ASC') {
      this.scoreSortDirection.set(null);
    } else {
      this.scoreSortDirection.set('DESC');
    }
  }

  // Lasso Selection Logic
  onMarqueeMouseDown(event: MouseEvent): void {
    const target = event.target as HTMLElement;

    const isRow = target.closest('.alert-row');
    const isInteractive = target.closest('button, a, select, input, .dropdown-menu, .no-lasso');

    if (isRow || isInteractive) return;

    this.isMarqueeSelecting.set(true);
    this.startX = event.clientX;
    this.startY = event.clientY;
    this.marqueeBox.set({ x1: this.startX, y1: this.startY, x2: this.startX, y2: this.startY });

    // Prevent text selection ONLY when starting marquee from background
    event.preventDefault();
  }

  @HostListener('window:mousemove', ['$event'])
  onMarqueeMouseMove(event: MouseEvent): void {
    if (!this.isMarqueeSelecting()) return;

    this.marqueeBox.set({
      x1: this.startX,
      y1: this.startY,
      x2: event.clientX,
      y2: event.clientY
    });

    this.updateSelectionFromMarquee();
  }

  @HostListener('window:mouseup')
  onMarqueeMouseUp(): void {
    if (!this.isMarqueeSelecting()) return;
    this.isMarqueeSelecting.set(false);
    this.marqueeBox.set(null);
  }

  private updateSelectionFromMarquee(): void {
    const box = this.marqueeBox();
    if (!box) return;

    const xMin = Math.min(box.x1, box.x2);
    const xMax = Math.max(box.x1, box.x2);
    const yMin = Math.min(box.y1, box.y2);
    const yMax = Math.max(box.y1, box.y2);

    const rows = document.querySelectorAll('.alert-row');
    const newlySelected = new Set<string>();

    rows.forEach(row => {
      const rect = row.getBoundingClientRect();
      const uuid = row.getAttribute('data-id');
      if (!uuid) return;

      // Check for overlap
      const overlap = !(rect.right < xMin ||
        rect.left > xMax ||
        rect.bottom < yMin ||
        rect.top > yMax);

      if (overlap) {
        newlySelected.add(uuid);
      }
    });

    const currentList = Array.from(newlySelected);
    this.selectedAlertUuids.set(new Set(currentList));
  }

  getMarqueeStyle() {
    const box = this.marqueeBox();
    if (!box) return { display: 'none' };

    const x = Math.min(box.x1, box.x2);
    const y = Math.min(box.y1, box.y2);
    const w = Math.abs(box.x2 - box.x1);
    const h = Math.abs(box.y2 - box.y1);

    return {
      position: 'fixed',
      left: x + 'px',
      top: y + 'px',
      width: w + 'px',
      height: h + 'px',
      border: '1px solid #4f46e5',
      backgroundColor: 'rgba(79, 70, 229, 0.15)',
      pointerEvents: 'none',
      zIndex: 9999,
      borderRadius: '2px'
    };
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

    const headers = ['ID Transaction;Nom Client;Date de Création;Type d\'Alerte;Montant;Niveau de Risque;Score de Fraude;Statut;Analyste Assigné;Commentaire'];

    const rows = data.map((alert: FraudAlertResponse) => [
      `TXN-${alert.transactionId ?? ''}`,
      alert.clientName || `CLIENT-${alert.userId}`,
      new Date(alert.createdAt).toLocaleString('fr-FR'),
      this.translateAlertType(alert.alertType),
      `${(alert.transactionAmount ?? 0).toFixed(2)} ${alert.transactionCurrency || 'TND'}`,
      this.translateRisk(alert.riskLevel),
      `${(alert.fraudScore * 100).toFixed(0)}%`,
      this.translateStatus(alert.status),
      alert.assignedAnalystName || 'Non assignée',
      (alert.reviewComment || '').replace(/;/g, ',').replace(/\n/g, ' ')
    ].join(';'));

    const csvContent = [headers, ...rows].join('\r\n');
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