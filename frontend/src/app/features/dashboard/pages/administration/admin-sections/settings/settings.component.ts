import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../../environments/environment';
import { AuthStore } from '../../../../../../core/store/auth.store';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';

interface SystemSettings {
  security: {
    sessionTimeout: string;
    auditLogEnabled: boolean;
  };
  system: {
    dataRetention: string;
    autoBackup: boolean;
    emailNotifications: boolean;
  };
  notifications: {
    criticalAlerts: boolean;
    dailyReports: boolean;
    modelDrift: boolean;
    versionUpdates: boolean;
  };
}

interface SystemSetting {
  key: string;
  value: string;
}

interface Archive {
  id: number;
  type: string;
  startAt: string;
  endAt: string;
  objectKey: string;
  status: string;
  createdAt: string;
}

interface ArchivePageResponse {
  content: Archive[];
  totalElements: number;
}

@Component({
  selector: 'app-settings',
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css'
})
export class SettingsComponent implements OnInit {
  private http = inject(HttpClient);
  authStore = inject(AuthStore);

  isLoading = signal(true);
  saving = signal(false);
  showDeleteConfirm = signal(false);
  archives = signal<Archive[]>([]);
  selectedRollbackId = signal<number | null>(null);
  showPreviewModal = signal(false);
  previewData = signal<any>(null);

  settings: SystemSettings = {
    security: {
      sessionTimeout: '30',
      auditLogEnabled: true
    },
    system: {
      dataRetention: '90',
      autoBackup: true,
      emailNotifications: true
    },
    notifications: {
      criticalAlerts: true,
      dailyReports: true,
      modelDrift: true,
      versionUpdates: false
    }
  };

  ngOnInit(): void {
    this.loadSettings();
  }

  loadSettings(): void {
    this.isLoading.set(true);

    // Charger les paramètres depuis le backend
    this.http.get<SystemSetting[]>(`${environment.apiUrl}/settings`).subscribe({
      next: (response) => {
        this.mapSettingsFromResponse(response);
        this.isLoading.set(false);
      },
      error: (error) => {
        console.error('Error loading settings:', error);
        this.isLoading.set(false);
      }
    });

    // Charger spécifiquement l'état des logs
    this.http.get<{ enabled: boolean }>(`${environment.apiUrl}/settings/audit-log`).subscribe({
      next: (response) => {
        this.settings.security.auditLogEnabled = response.enabled;
      },
      error: (error) => console.error('Error loading audit log status:', error)
    });

    // Charger les archives pour le rollback
    this.http.get<ArchivePageResponse>(`${environment.apiUrl}/archives?size=100`).subscribe({
      next: (response) => {
        this.archives.set(response.content);
      },
      error: (error) => console.error('Error loading archives:', error)
    });
  }

  mapSettingsFromResponse(response: SystemSetting[]): void {
    if (!response || !Array.isArray(response)) return;

    const settingsMap: { [key: string]: string } = {};
    response.forEach(s => {
      settingsMap[s.key] = s.value;
    });

    this.settings.security.sessionTimeout = settingsMap['session_timeout'] || '30';
    this.settings.system.dataRetention = settingsMap['retention_days'] || '90';
    this.settings.system.autoBackup = settingsMap['archive_enabled'] === 'true';
    this.settings.system.emailNotifications = settingsMap['email_notifications'] === 'true';

    this.settings.notifications.criticalAlerts = settingsMap['critical_alerts'] === 'true';
    this.settings.notifications.dailyReports = settingsMap['daily_reports'] === 'true';
    this.settings.notifications.modelDrift = settingsMap['model_drift'] === 'true';
    this.settings.notifications.versionUpdates = settingsMap['version_updates'] === 'true';
  }

  toggleAuditLog(event: any): void {
    const enabled = event.target.checked;
    this.settings.security.auditLogEnabled = enabled;

    this.http.patch(`${environment.apiUrl}/settings/audit-log`, { enabled })
      .subscribe({
        next: () => {
          console.log('Audit log status updated:', enabled);
        },
        error: (error) => {
          console.error('Error updating audit log status:', error);
          this.settings.security.auditLogEnabled = !enabled;
          event.target.checked = !enabled;
        }
      });
  }

  updateSetting(key: string, event: any): void {
    let value: string;

    if (event.target) {
      if (event.target.type === 'checkbox') {
        value = event.target.checked ? 'true' : 'false';
      } else {
        value = event.target.value;
      }
    } else {
      value = event;
    }

    // Modif de status d'activation
    if (key === 'archive_enabled') {
      this.settings.system.autoBackup = value === 'true';
    } else if (key === 'email_notifications') {
      this.settings.system.emailNotifications = value === 'true';
    }

    this.saving.set(true);

    this.http.patch(`${environment.apiUrl}/settings/${key}`, { value })
      .subscribe({
        next: () => {
          this.saving.set(false);
        },
        error: (error) => {
          console.error(`Error updating setting ${key}:`, error);
          this.saving.set(false);
          this.loadSettings();
        }
      });
  }

  deleteAllArchives(): void {
    this.saving.set(true);
    this.http.delete(`${environment.apiUrl}/archives`).subscribe({
      next: () => {
        this.saving.set(false);
        this.showDeleteConfirm.set(false);
        this.archives.set([]);
        alert('Toutes les archives ont été supprimées avec succès.');
        this.loadSettings();
      },
      error: (error) => {
        console.error('Error deleting archives:', error);
        this.saving.set(false);
        this.showDeleteConfirm.set(false);
        alert('Erreur lors de la suppression des archives.');
      }
    });
  }

  deleteArchive(id: number): void {
    if (confirm('Êtes-vous sûr de vouloir supprimer cette archive ?')) {
      this.http.delete(`${environment.apiUrl}/archives/${id}`).subscribe({
        next: () => {
          this.archives.update(list => list.filter(a => a.id !== id));
        },
        error: (error) => {
          console.error('Error deleting archive:', error);
          alert('Erreur lors de la suppression de l\'archive.');
        }
      });
    }
  }

  previewRollback(): void {
    const id = this.selectedRollbackId();
    if (!id) {
      alert('Veuillez sélectionner un point de restauration pour prévisualiser.');
      return;
    }

    this.http.get<any>(`${environment.apiUrl}/archives/${id}/preview`).subscribe({
      next: (data) => {
        this.previewData.set(data);
        this.showPreviewModal.set(true);
      },
      error: (error) => {
        console.error('Error previewing archive:', error);
        alert('Erreur lors de la prévisualisation de l\'archive.');
      }
    });
  }

  executeRollback(): void {
    const id = this.selectedRollbackId();
    if (!id) {
      alert('Veuillez sélectionner un point de restauration.');
      return;
    }

    if (confirm('Êtes-vous sûr de vouloir restaurer cette version ? Cette action peut entraîner une perte de données récentes.')) {
      this.saving.set(true);
      this.http.post<any>(`${environment.apiUrl}/archives/${id}/restore`, {}).subscribe({
        next: () => {
          this.saving.set(false);
          alert('Restauration réussie !');
          this.loadSettings();
        },
        error: (error) => {
          console.error('Error executing rollback:', error);
          this.saving.set(false);
          const msg = error?.error?.error || 'Erreur inconnue lors de la restauration.';
          alert('Erreur lors de la restauration: ' + msg);
        }
      });
    }
  }
}