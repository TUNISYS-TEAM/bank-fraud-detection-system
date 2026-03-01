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

  isLoading = computed(() => this.loading());

  ngOnInit(): void {
    this.loadSessions();
  }

  loadSessions(): void {
    this.loading.set(true);
    // Endpoint à adapter selon votre API
    this.http.get<Session[]>(`${environment.apiUrl}/sessions`).subscribe({
      next: (sessions) => {
        this.sessions.set(sessions);
        this.loading.set(false);
      },
      error: (error) => {
        console.error('Error loading sessions:', error);
        this.loading.set(false);
      }
    });
  }

  terminateSession(session: Session): void {
    this.selectedSession.set(session);
    this.showTerminateDialog.set(true);
  }

  confirmTerminate(): void {
    const session = this.selectedSession();
    if (!session) return;

    // Endpoint à adapter selon votre API
    this.http.delete(`${environment.apiUrl}/sessions/${session.id}`).subscribe({
      next: () => {
        // Mettre à jour la liste des sessions
        this.sessions.update(sessions =>
          sessions.map(s =>
            s.id === session.id
              ? { ...s, isActive: false, logoutTime: new Date().toISOString() }
              : s
          )
        );
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
      this.http.delete(`${environment.apiUrl}/sessions/all`).subscribe({
        next: () => {
          this.loadSessions();
        },
        error: (error) => {
          console.error('Error terminating all sessions:', error);
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
    if (userAgent.includes('Chrome')) return 'Chrome';
    if (userAgent.includes('Firefox')) return 'Firefox';
    if (userAgent.includes('Safari')) return 'Safari';
    if (userAgent.includes('Edge')) return 'Edge';
    if (userAgent.includes('OPR')) return 'Opera';
    return 'Autre';
  }

  getOSInfo(userAgent: string): string {
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
    navigator.clipboard.writeText(text);
  }
}