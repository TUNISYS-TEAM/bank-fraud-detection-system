import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../../environments/environment';
import { AuthStore } from '../../../../../../core/store/auth.store';

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

// Interface pour la réponse des settings
interface SettingsResponse {
  [key: string]: string;
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

    // Charger les paramètres depuis le backend - utiliser le bon type
    this.http.get<SettingsResponse>(`${environment.apiUrl}/settings`).subscribe({
      next: (response) => {
        this.mapSettingsFromResponse(response);
        this.isLoading.set(false);
      },
      error: (error) => {
        console.error('Error loading settings:', error);
        this.isLoading.set(false);
        // Fallback sur les valeurs par défaut
      }
    });

    // Charger spécifiquement l'état des logs
    this.http.get<{ enabled: boolean }>(`${environment.apiUrl}/settings/audit-log`).subscribe({
      next: (response) => {
        this.settings.security.auditLogEnabled = response.enabled;
      },
      error: (error) => console.error('Error loading audit log status:', error)
    });
  }

  mapSettingsFromResponse(response: SettingsResponse): void {
    // Utiliser la notation par index avec l'interface SettingsResponse
    this.settings.security.sessionTimeout = response['session_timeout'] || '30';
    this.settings.system.dataRetention = response['data_retention'] || '90';
    this.settings.system.autoBackup = response['auto_backup'] === 'true';
    this.settings.system.emailNotifications = response['email_notifications'] === 'true';
    this.settings.notifications.criticalAlerts = response['critical_alerts'] === 'true';
    this.settings.notifications.dailyReports = response['daily_reports'] === 'true';
    this.settings.notifications.modelDrift = response['model_drift'] === 'true';
    this.settings.notifications.versionUpdates = response['version_updates'] === 'true';
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
          // Revert on error
          this.settings.security.auditLogEnabled = !enabled;
          event.target.checked = !enabled;
        }
      });
  }

  updateSetting(key: string, event: any): void {
    let value: string;
    
    if (event.target) {
      // Pour les checkboxes
      if (event.target.type === 'checkbox') {
        value = event.target.checked ? 'true' : 'false';
      } else {
        // Pour les selects
        value = event.target.value;
      }
    } else {
      // Pour les appels directs avec une valeur
      value = event;
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
          // Recharger les paramètres pour annuler les changements locaux
          this.loadSettings();
        }
      });
  }

  previewRollback(): void {
    // Logique de prévisualisation du rollback
    console.log('Preview rollback');
    // TODO: Implémenter la prévisualisation
  }

  executeRollback(): void {
    // Logique d'exécution du rollback
    console.log('Execute rollback');
    // TODO: Implémenter le rollback avec confirmation
    if (confirm('Êtes-vous sûr de vouloir restaurer cette version ? Cette action peut entraîner une perte de données récentes.')) {
      // Exécuter le rollback
    }
  }
}