import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../../environments/environment';
import { AuthStore } from '../../../../../../core/store/auth.store';

interface Session {
  id: number;
  sessionId: string;
  keycloakSessionId: string;
  ipAddress: string;
  userAgent: string;
  loginTime: string;
  lastActivity: string;
  logoutTime: string | null;
  isActive: boolean;
  userEmail?: string;
  userName?: string;
}

interface ApiResponse {
  content: Session[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

@Component({
  selector: 'app-sessions',
  templateUrl: './sessions.component.html',
  styleUrl: './sessions.component.css'
})
export class SessionsComponent implements OnInit {
  private http = inject(HttpClient);
  authStore = inject(AuthStore);

  sessions = signal<Session[]>([]);
  loading = signal(false);
  selectedSession = signal<Session | null>(null);
  showTerminateDialog = signal(false);
  
  // Pagination
  currentPage = signal(0);
  pageSize = 5;
  totalElements = signal(0);
  totalPages = signal(0);
  activeOnly = signal<boolean | undefined>(undefined);

  isLoading = computed(() => this.loading());
  
  displayRange = computed(() => {
    const start = this.currentPage() * this.pageSize + 1;
    const end = Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());
    return { start, end };
  });

  ngOnInit(): void {
    this.loadSessions();
  }

  loadSessions(): void {
    this.loading.set(true);
    
    let url = `${environment.apiUrl}/sessions?page=${this.currentPage()}&size=${this.pageSize}`;
    
    // Add activeOnly filter if set
    if (this.activeOnly() !== undefined) {
      url += `&activeOnly=${this.activeOnly()}`;
    }
    
    this.http.get<ApiResponse>(url).subscribe({
      next: (response) => {
        this.sessions.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
      },
      error: (error) => {
        console.error('Error loading sessions:', error);
        this.loading.set(false);
      }
    });
  }

  onPageChange(page: number): void {
    this.currentPage.set(page);
    this.loadSessions();
  }

  toggleActiveFilter(): void {
    const current = this.activeOnly();
    if (current === undefined) {
      this.activeOnly.set(true);
    } else if (current === true) {
      this.activeOnly.set(false);
    } else {
      this.activeOnly.set(undefined);
    }
    this.currentPage.set(0);
    this.loadSessions();
  }

  getFilterButtonText(): string {
    const current = this.activeOnly();
    if (current === undefined) return 'Toutes';
    if (current === true) return 'Actives';
    return 'Inactives';
  }

  terminateSession(session: Session): void {
    this.selectedSession.set(session);
    this.showTerminateDialog.set(true);
  }

  confirmTerminate(): void {
    const session = this.selectedSession();
    if (!session) return;

    this.http.delete(`${environment.apiUrl}/sessions/${session.sessionId}`).subscribe({
      next: () => {
        // Reload sessions to get updated data
        this.loadSessions();
        this.showTerminateDialog.set(false);
        this.selectedSession.set(null);
      },
      error: (error) => {
        console.error('Error terminating session:', error);
        this.showTerminateDialog.set(false);
      }
    });
  }

  cancelTerminate(): void {
    this.showTerminateDialog.set(false);
    this.selectedSession.set(null);
  }

  terminateAllSessions(): void {
    if (confirm('Êtes-vous sûr de vouloir terminer toutes les sessions actives ?')) {
      // Note: You'll need to implement this endpoint if needed
      // For now, this would need a userId parameter
      console.warn('terminateAllSessions not implemented - needs userId');
    }
  }

  syncWithKeycloak(): void {
    if (confirm('Synchroniser toutes les sessions avec Keycloak ?')) {
      this.http.post(`${environment.apiUrl}/sessions/sync`, {}).subscribe({
        next: () => {
          this.loadSessions();
        },
        error: (error) => {
          console.error('Error syncing sessions:', error);
        }
      });
    }
  }

  refreshSessions(): void {
    this.loadSessions();
  }

  formatDate(date: string | null): string {
    if (!date) return '—';
    return new Date(date).toLocaleString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    });
  }

  getBrowserInfo(userAgent: string): string {
    if (!userAgent) return 'Inconnu';
    if (userAgent.includes('Chrome')) return 'Chrome';
    if (userAgent.includes('Firefox')) return 'Firefox';
    if (userAgent.includes('Safari')) return 'Safari';
    if (userAgent.includes('Edge')) return 'Edge';
    if (userAgent.includes('OPR')) return 'Opera';
    return 'Autre';
  }

  getOSInfo(userAgent: string): string {
    if (!userAgent) return 'Inconnu';
    if (userAgent.includes('Windows')) return 'Windows';
    if (userAgent.includes('Mac')) return 'macOS';
    if (userAgent.includes('Linux')) return 'Linux';
    if (userAgent.includes('Android')) return 'Android';
    if (userAgent.includes('iOS')) return 'iOS';
    return 'Autre';
  }

  getStatusBadgeClass(isActive: boolean): string {
    return isActive
      ? 'bg-green-50 text-green-700 border-green-200'
      : 'bg-gray-100 text-gray-600 border-gray-200';
  }

  getStatusText(isActive: boolean): string {
    return isActive ? 'Actif' : 'Terminé';
  }

  getDuration(loginTime: string, logoutTime: string | null): string {
    const start = new Date(loginTime).getTime();
    const end = logoutTime ? new Date(logoutTime).getTime() : Date.now();
    const durationMs = end - start;

    const hours = Math.floor(durationMs / (1000 * 60 * 60));
    const minutes = Math.floor((durationMs % (1000 * 60 * 60)) / (1000 * 60));

    if (hours > 0) {
      return `${hours}h ${minutes}min`;
    }
    return `${minutes}min`;
  }

  copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      console.log('Copied to clipboard:', text);
    });
  }

  getPageNumbers(): number[] {
    const total = this.totalPages();
    const current = this.currentPage() + 1;
    const delta = 2;
    const range: number[] = [];
    const rangeWithDots: number[] = [];
    let l: number;

    for (let i = 1; i <= total; i++) {
      if (i === 1 || i === total || (i >= current - delta && i <= current + delta)) {
        range.push(i);
      }
    }

    range.forEach((i) => {
      if (l) {
        if (i - l === 2) {
          rangeWithDots.push(l + 1);
        } else if (i - l !== 1) {
          rangeWithDots.push(-1);
        }
      }
      rangeWithDots.push(i);
      l = i;
    });

    return rangeWithDots;
  }
}