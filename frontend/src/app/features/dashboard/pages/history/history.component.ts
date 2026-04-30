import { Component, OnInit, signal, inject } from '@angular/core';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { NotificationService, Notification } from '../../../../core/services/notification.service';

@Component({
  selector: 'app-history',
  templateUrl: './history.component.html',
  styleUrls: ['./history.component.css']
})
export class HistoryComponent implements OnInit {
  private notificationService = inject(NotificationService);
  private router = inject(Router);

  notifications = signal<Notification[]>([]);
  filteredNotifications = signal<Notification[]>([]);
  isLoading = signal(true);
  selectedType = signal<string>('all');
  searchTerm = signal<string>('');
  selectedNotifications = signal<Set<string>>(new Set());

  notificationTypes = [
    { value: 'all', label: 'Toutes' },
    { value: 'NEW_MESSAGE', label: 'Messages' },
    { value: 'VALIDATION_REQUEST', label: 'Demandes de validation' },
    { value: 'MODEL_VALIDATED', label: 'Modèles validés' },
    { value: 'MODEL_REJECTED', label: 'Modèles rejetés' },
    { value: 'NEW_REPORT', label: 'Rapports' }
  ];

  ngOnInit() {
    this.loadNotifications();
  }

  loadNotifications() {
    this.isLoading.set(true);
    this.notificationService.loadAllUserNotifications().subscribe({
      next: (notifications: Notification[]) => {
        this.notifications.set(notifications);
        this.applyFilters();
        this.isLoading.set(false);
      },
      error: (err: any) => {
        console.error('Failed to load notifications', err);
        this.isLoading.set(false);
      }
    });
  }

  applyFilters() {
    let filtered = [...this.notifications()];
    if (this.selectedType() !== 'all') {
      filtered = filtered.filter(n => n.type === this.selectedType());
    }
    const search = this.searchTerm().toLowerCase();
    if (search) {
      filtered = filtered.filter(n =>
        n.title.toLowerCase().includes(search) ||
        n.message.toLowerCase().includes(search)
      );
    }
    this.filteredNotifications.set(filtered);
  }

  onTypeChange() { this.applyFilters(); }
  onSearch() { this.applyFilters(); }

  toggleSelectAll(event: Event) {
    const checked = (event.target as HTMLInputElement).checked;
    if (checked) {
      const allUuids = this.filteredNotifications().map(n => n.notificationUuid);
      this.selectedNotifications.set(new Set(allUuids));
    } else {
      this.selectedNotifications.set(new Set());
    }
  }

  toggleSelectOne(uuid: string, event: Event) {
    const checked = (event.target as HTMLInputElement).checked;
    const current = new Set(this.selectedNotifications());
    if (checked) current.add(uuid);
    else current.delete(uuid);
    this.selectedNotifications.set(current);
  }

  isSelected(uuid: string): boolean {
    return this.selectedNotifications().has(uuid);
  }

  markSelectedAsRead() {
    const uuids = Array.from(this.selectedNotifications());
    if (uuids.length === 0) return;

    const requests = uuids.map(uuid => this.notificationService.markAsReadObservable(uuid));
    
    forkJoin(requests).subscribe({
      next: () => {
        this.notifications.update(list =>
          list.map(n => 
            uuids.includes(n.notificationUuid) ? { ...n, isRead: true, read: true } : n
          )
        );
        this.applyFilters();
        this.selectedNotifications.set(new Set());
      },
      error: (err: any) => console.error('Failed to mark selected as read', err)
    });
  }

  deleteSelected() {
    const uuids = Array.from(this.selectedNotifications());
    if (uuids.length === 0) return;
    
    if (confirm(`Supprimer ${uuids.length} notification(s) ?`)) {
      const requests = uuids.map(uuid => this.notificationService.deleteNotificationObservable(uuid));
      
      forkJoin(requests).subscribe({
        next: () => {
          this.notifications.update(list =>
            list.filter(n => !uuids.includes(n.notificationUuid))
          );
          this.applyFilters();
          this.selectedNotifications.set(new Set());
        },
        error: (err: any) => console.error('Failed to delete selected notifications', err)
      });
    }
  }

  markAsRead(uuid: string) {
    this.notificationService.markAsReadObservable(uuid).subscribe({
      next: () => {
        this.notifications.update(list => 
          list.map(n => n.notificationUuid === uuid ? { ...n, isRead: true, read: true } : n)
        );
        this.applyFilters();
      },
      error: (err: any) => console.error('Failed to mark as read', err)
    });
  }

  deleteNotification(uuid: string) {
    if (confirm('Supprimer cette notification ?')) {
      this.notificationService.deleteNotificationObservable(uuid).subscribe({
        next: () => {
          this.notifications.update(list => list.filter(n => n.notificationUuid !== uuid));
          this.applyFilters();
        },
        error: (err: any) => console.error('Failed to delete notification', err)
      });
    }
  }

  markAllAsRead() {
    this.notificationService.markAllAsRead().subscribe({
      next: () => {
        this.notifications.update(list =>
          list.map(n => ({ ...n, isRead: true, read: true }))
        );
        this.applyFilters();
      },
      error: (err: any) => console.error('Failed to mark all as read', err)
    });
  }

  deleteAll() {
    if (confirm('Supprimer toutes vos notifications ?')) {
      this.notificationService.deleteAllNotifications().subscribe({
        next: () => {
          this.notifications.set([]);
          this.filteredNotifications.set([]);
          this.selectedNotifications.set(new Set());
          this.applyFilters();
        },
        error: (err: any) => console.error('Failed to delete all notifications', err)
      });
    }
  }

  navigateToNotification(notification: Notification) {
    if (!notification.isRead) {
      this.markAsRead(notification.notificationUuid);
    }

    const payload = notification.payload;
    switch (notification.type) {
      case 'NEW_MESSAGE':
        if (payload?.messageUuid) {
          this.router.navigate(['/dashboard/messages'], {
            queryParams: { messageUuid: payload.messageUuid }
          });
        } else {
          this.router.navigate(['/dashboard/messages']);
        }
        break;
      case 'MODEL_VALIDATED':
      case 'MODEL_REJECTED':
      case 'VALIDATION_REQUEST':
        if (payload?.modelUuid) {
          this.router.navigate(['/dashboard/messages'], {
            queryParams: { tab: 'validation', modelUuid: payload.modelUuid }
          });
        } else {
          this.router.navigate(['/dashboard/messages'], { queryParams: { tab: 'validation' } });
        }
        break;
      case 'NEW_REPORT':
        if (payload?.rapportUuid) {
          this.router.navigate(['/dashboard/messages'], {
            queryParams: { tab: 'reporting', rapportUuid: payload.rapportUuid }
          });
        } else {
          this.router.navigate(['/dashboard/messages'], { queryParams: { tab: 'reporting' } });
        }
        break;
      default:
        this.router.navigate(['/dashboard/messages']);
    }
  }

  getIconForType(type: string): string {
    switch (type) {
      case 'NEW_MESSAGE': return 'message-circle';
      case 'VALIDATION_REQUEST': return 'clipboard';
      case 'MODEL_VALIDATED': return 'check-circle';
      case 'MODEL_REJECTED': return 'x-circle';
      case 'NEW_REPORT': return 'file-text';
      default: return 'bell';
    }
  }

  getBadgeClass(type: string): string {
    switch (type) {
      case 'NEW_MESSAGE': return 'bg-blue-100 text-blue-700';
      case 'VALIDATION_REQUEST': return 'bg-yellow-100 text-yellow-700';
      case 'MODEL_VALIDATED': return 'bg-green-100 text-green-700';
      case 'MODEL_REJECTED': return 'bg-red-100 text-red-700';
      case 'NEW_REPORT': return 'bg-emerald-100 text-emerald-700';
      default: return 'bg-gray-100 text-gray-700';
    }
  }

  getStatusLabel(isRead: boolean): string {
    return isRead ? 'Lu' : 'Non lu';
  }

  getStatusClass(isRead: boolean): string {
    return isRead ? 'text-green-600 bg-green-50' : 'text-orange-600 bg-orange-50';
  }

  getTypeLabel(type: string): string {
    switch (type) {
      case 'NEW_MESSAGE': return 'Message';
      case 'VALIDATION_REQUEST': return 'Validation';
      case 'MODEL_VALIDATED': return 'Validé';
      case 'MODEL_REJECTED': return 'Rejeté';
      case 'NEW_REPORT': return 'Rapport';
      default: return type;
    }
  }
}