import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../../../../environments/environment';
import { AuthStore } from '../../../../../../core/store/auth.store';
import { finalize } from 'rxjs';

interface SystemSettings {
  security: {
    sessionTimeout: string;
    auditLogEnabled: boolean;
  };
  system: {
    dataRetention: string;
    autoBackup: boolean;
  };
  notifications: {
    emailNotificationsEnabled: boolean;
    criticalAlerts: boolean;
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
      autoBackup: true
    },
    notifications: {
      emailNotificationsEnabled: true,
      criticalAlerts: true,
      modelDrift: true,
      versionUpdates: false
    }
  };

  ngOnInit(): void {
    this.loadSettings();
  }

  loadSettings(): void {
    this.isLoading.set(true);

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

    this.http.get<{ enabled: boolean }>(`${environment.apiUrl}/settings/audit-log`).subscribe({
      next: (response) => {
        this.settings.security.auditLogEnabled = response.enabled;
      },
      error: (error) => console.error('Error loading audit log status:', error)
    });

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
    
    this.settings.notifications.emailNotificationsEnabled = settingsMap['email_notifications_enabled'] === 'true';
    this.settings.notifications.criticalAlerts = settingsMap['critical_alerts'] === 'true';
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

  // Master toggle: When turned OFF, turn off all children
  // When turned ON, turn on all children
  toggleMasterEmailNotifications(event: any): void {
    const enabled = event.target.checked;
    this.saving.set(true);
    
    if (enabled) {
      // Turn ON all child notifications
      this.settings.notifications.emailNotificationsEnabled = true;
      this.settings.notifications.criticalAlerts = true;
      this.settings.notifications.modelDrift = true;
      this.settings.notifications.versionUpdates = true;
      
      // Batch update all settings
      const updates = {
        'email_notifications_enabled': 'true',
        'critical_alerts': 'true',
        'model_drift': 'true',
        'version_updates': 'true'
      };
      
      this.http.patch(`${environment.apiUrl}/settings/batch`, updates)
        .pipe(finalize(() => this.saving.set(false)))
        .subscribe({
          next: () => {
            console.log('All notifications enabled');
          },
          error: (error) => {
            console.error('Error enabling notifications:', error);
            this.loadSettings(); // Reload to revert
          }
        });
    } else {
      // Turn OFF all child notifications
      this.settings.notifications.emailNotificationsEnabled = false;
      this.settings.notifications.criticalAlerts = false;
      this.settings.notifications.modelDrift = false;
      this.settings.notifications.versionUpdates = false;
      
      // Batch update all settings
      const updates = {
        'email_notifications_enabled': 'false',
        'critical_alerts': 'false',
        'model_drift': 'false',
        'version_updates': 'false'
      };
      
      this.http.patch(`${environment.apiUrl}/settings/batch`, updates)
        .pipe(finalize(() => this.saving.set(false)))
        .subscribe({
          next: () => {
            console.log('All notifications disabled');
          },
          error: (error) => {
            console.error('Error disabling notifications:', error);
            this.loadSettings(); // Reload to revert
          }
        });
    }
  }

  // Child toggle: When turned ON, ensure master is ON
  // After child changes, check if all children are OFF to turn OFF master
  onChildNotificationChange(key: string, event: any): void {
    const enabled = event.target.checked;
    this.saving.set(true);
    
    // Update the local settings FIRST so UI updates immediately
    if (key === 'critical_alerts') {
      this.settings.notifications.criticalAlerts = enabled;
    } else if (key === 'model_drift') {
      this.settings.notifications.modelDrift = enabled;
    } else if (key === 'version_updates') {
      this.settings.notifications.versionUpdates = enabled;
    }
    
    // Prepare updates object
    const updates: { [key: string]: string } = {};
    let needsMasterUpdate = false;
    
    // If any child is turned ON, ensure master is ON
    if (enabled && !this.settings.notifications.emailNotificationsEnabled) {
      this.settings.notifications.emailNotificationsEnabled = true;
      updates['email_notifications_enabled'] = 'true';
      needsMasterUpdate = true;
    }
    
    // Add the child update
    updates[key] = enabled ? 'true' : 'false';
    
    // Send batch update
    this.http.patch(`${environment.apiUrl}/settings/batch`, updates)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          console.log('Notification settings updated:', updates);
          
          // After successful update, check if we need to check all children OFF
          if (!needsMasterUpdate) {
            // Re-check if all children are OFF to turn OFF master
            const allChildrenOff = !this.settings.notifications.criticalAlerts && 
                                   !this.settings.notifications.modelDrift && 
                                   !this.settings.notifications.versionUpdates;
            
            if (allChildrenOff && this.settings.notifications.emailNotificationsEnabled) {
              this.settings.notifications.emailNotificationsEnabled = false;
              this.http.patch(`${environment.apiUrl}/settings/batch`, {
                'email_notifications_enabled': 'false'
              }).subscribe({
                error: (err) => console.error('Error turning off master:', err)
              });
            }
          }
        },
        error: (error) => {
          console.error('Error updating notification settings:', error);
          this.loadSettings(); // Reload to revert
        }
      });
  }

  updateSetting(key: string, value: any): void {
    let stringValue: string;
    
    if (typeof value === 'boolean') {
      stringValue = value ? 'true' : 'false';
    } else if (value.target) {
      // It's an event from a DOM element
      if (value.target.type === 'checkbox') {
        stringValue = value.target.checked ? 'true' : 'false';
      } else {
        stringValue = value.target.value;
      }
    } else {
      stringValue = String(value);
    }

    // Update local state for non-notification settings
    if (key === 'archive_enabled') {
      this.settings.system.autoBackup = stringValue === 'true';
    } else if (key === 'retention_days') {
      this.settings.system.dataRetention = stringValue;
    } else if (key === 'session_timeout') {
      this.settings.security.sessionTimeout = stringValue;
    }

    this.saving.set(true);

    this.http.patch(`${environment.apiUrl}/settings/${key}`, { value: stringValue })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          console.log(`Setting ${key} updated to ${stringValue}`);
        },
        error: (error) => {
          console.error(`Error updating setting ${key}:`, error);
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