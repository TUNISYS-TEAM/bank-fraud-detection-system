import { Injectable, inject, signal, WritableSignal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { WebSocketService } from './websocket.service';
import { Router } from '@angular/router';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

export interface Notification {
  id?: number;
  notificationUuid: string;
  title: string;
  message: string;
  type: string;
  createdAt: string;
  isRead: boolean;
  readAt?: string | null;
  eventType?: string;
  eventId?: string;
  payload?: any;
  timestamp?: string;
  read?: boolean;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private http = inject(HttpClient);
  private wsService = inject(WebSocketService);
  private router = inject(Router);
  private authService = inject(AuthService);

  readonly notifications: WritableSignal<Notification[]> = signal([]);
  readonly unreadCount = signal(0);

  private isWebSocketReady = false;
  private isLoading = false;
  private currentUserEmail: string | null = null;

  constructor() {
    this.authService.user$.subscribe((user) => {
      if (user && user.email) {
        if (this.currentUserEmail !== user.email) {
          console.log('User changed to:', user.email);
          this.currentUserEmail = user.email;
          this.notifications.set([]);
          this.unreadCount.set(0);
          this.loadNotificationsFromBackend();
        }
      } else {
        if (this.currentUserEmail !== null) {
          console.log('User logged out, clearing notifications');
          this.currentUserEmail = null;
          this.notifications.set([]);
          this.unreadCount.set(0);
        }
      }
    });

    this.wsService.isConnected().subscribe((connected: boolean) => {
      if (connected && !this.isWebSocketReady) {
        this.isWebSocketReady = true;
        if (this.currentUserEmail) {
          this.loadNotificationsFromBackend();
        }
      }
    });

    this.wsService.notifications$.subscribe((notification: any) => {
      if (notification && this.isWebSocketReady) {
        this.addRealtimeNotification(notification);
      }
    });
  }

  private loadNotificationsFromBackend(): void {
    if (this.isLoading) return;
    this.isLoading = true;

    this.http.get<any>(`${environment.apiUrl}/notifications?page=0&size=50`, { withCredentials: true })
      .subscribe({
        next: (response) => {
          const notifications = response.content.map((item: any) => {
            const isReadValue = item.read === true || item.isRead === true;
            return {
              id: item.id,
              notificationUuid: item.notificationUuid,
              title: item.title,
              message: item.message,
              type: item.type,
              createdAt: item.createdAt,
              isRead: isReadValue,
              readAt: item.readAt,
              eventId: item.notificationUuid,
              eventType: item.type,
              timestamp: item.createdAt,
              read: isReadValue,
              payload: null
            };
          });
          this.notifications.set(notifications);
          this.updateUnreadCount();
          this.isLoading = false;
        },
        error: (err: any) => {
          console.error('Failed to load notifications', err);
          this.isLoading = false;
        }
      });
  }

  private updateUnreadCount(): void {
    const count = this.notifications().filter(n => !n.isRead).length;
    this.unreadCount.set(count);
  }

  private addRealtimeNotification(wsNotification: any): void {
    const exists = this.notifications().some(n => n.notificationUuid === wsNotification.eventId);
    if (exists) return;

    const newNotif: Notification = {
      notificationUuid: wsNotification.eventId,
      title: this.getTitleForEvent(wsNotification),
      message: this.getMessageForEvent(wsNotification),
      type: wsNotification.eventType,
      createdAt: wsNotification.timestamp || new Date().toISOString(),
      isRead: false,
      eventId: wsNotification.eventId,
      eventType: wsNotification.eventType,
      timestamp: wsNotification.timestamp || new Date().toISOString(),
      read: false,
      payload: wsNotification.payload
    };

    this.notifications.update(list => [newNotif, ...list]);
    this.updateUnreadCount();
    this.showBrowserNotification(newNotif);
  }

  private getTitleForEvent(event: any): string {
    switch (event.eventType) {
      case 'NEW_MESSAGE': return 'Nouveau message';
      case 'VALIDATION_REQUEST': return 'Demande de validation';
      case 'MODEL_VALIDATED': return 'Modèle validé';
      case 'MODEL_REJECTED': return 'Modèle rejeté';
      case 'NEW_REPORT': return 'Nouveau rapport';
      default: return 'Notification';
    }
  }

  private getMessageForEvent(event: any): string {
    const p = event.payload;
    switch (event.eventType) {
      case 'NEW_MESSAGE':
        return `De: ${p.senderName}\nSujet: ${p.subject}`;
      case 'VALIDATION_REQUEST':
        return `Modèle: ${p.modelName || p.modelUuid}`;
      case 'MODEL_VALIDATED':
        return `Modèle ${p.modelUuid} validé`;
      case 'MODEL_REJECTED':
        return `Modèle ${p.modelUuid} rejeté`;
      case 'NEW_REPORT':
        return `${p.title}\nDe: ${p.generatedByName}`;
      default:
        return 'Nouvelle notification';
    }
  }

  private showBrowserNotification(notification: Notification): void {
    if (Notification.permission !== 'granted') return;
    new Notification(notification.title, { body: notification.message, icon: '/assets/bell-icon.png' });
  }

  markAsRead(notificationUuid: string): void {
    this.notifications.update(list =>
      list.map(n =>
        n.notificationUuid === notificationUuid ? { ...n, isRead: true, read: true } : n
      )
    );
    this.updateUnreadCount();

    this.http.put(`${environment.apiUrl}/notifications/${notificationUuid}/read`, {}, { withCredentials: true })
      .subscribe({
        error: (err: any) => console.error('Failed to mark as read', err)
      });
  }

  markAllAsRead(): Observable<void> {
    return this.http.put<void>(`${environment.apiUrl}/notifications/read-all`, {}, { withCredentials: true });
  }

  deleteNotification(notificationUuid: string): void {
    this.http.delete(`${environment.apiUrl}/notifications/${notificationUuid}`, { withCredentials: true })
      .subscribe({
        next: () => {
          this.notifications.update(list =>
            list.filter(n => n.notificationUuid !== notificationUuid)
          );
          this.updateUnreadCount();
        },
        error: (err: any) => console.error('Failed to delete notification', err)
      });
  }

  deleteAllNotifications(): Observable<void> {
    return this.http.delete<void>(`${environment.apiUrl}/notifications/delete-all`, { withCredentials: true });
  }

  clearAll(): void {
    this.deleteAllNotifications().subscribe();
  }

  requestNotificationPermission() {
    if (Notification.permission !== 'granted' && Notification.permission !== 'denied') {
      Notification.requestPermission();
    }
  }

  handleNotificationClick(notification: Notification): void {
    this.markAsRead(notification.notificationUuid);

    if (notification.type === 'NEW_MESSAGE' && notification.payload?.messageUuid) {
      this.router.navigate(['/dashboard/messages'], {
        queryParams: { messageUuid: notification.payload.messageUuid }
      });
    } else if (notification.type === 'VALIDATION_REQUEST' && notification.payload?.modelUuid) {
      this.router.navigate(['/dashboard/messages'], {
        queryParams: { tab: 'validation', modelUuid: notification.payload.modelUuid }
      });
    }
  }

  loadAllUserNotifications(): Observable<Notification[]> {
    return this.http.get<any>(`${environment.apiUrl}/notifications?page=0&size=1000`, { withCredentials: true })
      .pipe(
        map((response: any) => response.content.map((item: any) => ({
          id: item.id,
          notificationUuid: item.notificationUuid,
          title: item.title,
          message: item.message,
          type: item.type,
          createdAt: item.createdAt,
          isRead: item.read === true || item.isRead === true,
          readAt: item.readAt,
          eventId: item.notificationUuid,
          eventType: item.type,
          timestamp: item.createdAt,
          read: item.read === true || item.isRead === true,
          payload: null
        })))
      );
  }

  markAsReadObservable(notificationUuid: string): Observable<void> {
    return this.http.put<void>(`${environment.apiUrl}/notifications/${notificationUuid}/read`, {}, { withCredentials: true });
  }

  deleteNotificationObservable(notificationUuid: string): Observable<void> {
    return this.http.delete<void>(`${environment.apiUrl}/notifications/${notificationUuid}`, { withCredentials: true });
  }
}