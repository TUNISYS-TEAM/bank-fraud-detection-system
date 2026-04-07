import { Component, inject, signal, HostListener, ElementRef } from '@angular/core';
import { AuthStore } from '../../../core/store/auth.store';
import { NotificationService, Notification } from '../../../core/services/notification.service';

@Component({
  selector: 'app-header',
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.css']
})
export class HeaderComponent {
  readonly authStore = inject(AuthStore);
  private notificationService = inject(NotificationService);
  private elementRef = inject(ElementRef);

  showNotifications = signal(false);
  unreadCount = this.notificationService.unreadCount;
  notifications = this.notificationService.notifications;

  @HostListener('document:click', ['$event'])
  onClickOutside(event: MouseEvent) {
    // Check if click is inside the notification panel or on the bell button
    const clickedInside = this.elementRef.nativeElement.querySelector('.notification-container')?.contains(event.target);
    if (!clickedInside && this.showNotifications()) {
      this.showNotifications.set(false);
    }
  }

  toggleNotificationsPanel() {
    this.showNotifications.update(v => !v);
    if (this.showNotifications()) {
      this.notificationService.requestNotificationPermission();
    }
  }

  markAsRead(notification: Notification) {
    this.notificationService.markAsRead(notification.notificationUuid);
  }

  markAllAsRead() {
    this.notificationService.markAllAsRead();
  }

  clearAll() {
    this.notificationService.deleteAllNotifications();
  }

  onNotificationClick(notification: Notification) {
    this.notificationService.handleNotificationClick(notification);
    this.showNotifications.set(false);
  }

  getNotificationIcon(eventType: string): string {
    switch (eventType) {
      case 'NEW_MESSAGE': return 'message-circle';
      case 'VALIDATION_REQUEST': return 'clipboard';
      case 'MODEL_VALIDATED': return 'check-circle';
      case 'MODEL_REJECTED': return 'x-circle';
      default: return 'bell';
    }
  }

  getNotificationTitle(notification: Notification): string {
    return notification.title;
  }

  getNotificationDescription(notification: Notification): string {
    return notification.message;
  }
}