import { Component, OnInit, inject, signal, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MessageService } from '../../../../core/services/message.service';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { AuthStore } from '../../../../core/store/auth.store';
import { Message, SendMessageRequest } from '../../../../core/models/message.model';
import { Subscription } from 'rxjs';
import { environment } from '../../../../../environments/environment';

export interface User {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  roleId: number;
  isActive: boolean;
}

@Component({
  selector: 'app-messages',
  templateUrl: './messaging.component.html',
  styleUrls: ['./messaging.component.css']
})
export class MessagesComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private messageService = inject(MessageService);
  private wsService = inject(WebSocketService);
  authStore = inject(AuthStore);

  // State
  inbox = signal<Message[]>([]);
  sent = signal<Message[]>([]);
  currentTab = signal<'inbox' | 'sent' | 'compose'>('inbox');
  selectedMessage = signal<Message | null>(null);
  replyContent = signal('');
  loading = signal(false);
  
  // Pagination
  inboxPage = signal(0);
  inboxTotalPages = signal(0);
  sentPage = signal(0);
  sentTotalPages = signal(0);
  
  // New message
  newMessage = signal<{
    receiverId: number | null;
    subject: string;
    content: string;
    priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
  }>({
    receiverId: null,
    subject: '',
    content: '',
    priority: 'NORMAL'
  });

  // Users list (pour le compose)
  users = signal<User[]>([]);
  loadingUsers = signal(false);

  private wsSubscription: Subscription;

  constructor() {
    this.wsSubscription = this.wsService.messages$.subscribe(event => {
      if (event?.type === 'NEW_MESSAGE') {
        this.loadInbox();
        this.loadUnreadCount();
      }
    });
  }

  ngOnInit(): void {
    this.wsService.connect();
    this.loadInbox();
    this.loadUnreadCount();
    this.loadUsers();
  }

  ngOnDestroy(): void {
    this.wsSubscription?.unsubscribe();
    this.wsService.disconnect();
  }

  loadInbox(): void {
    this.loading.set(true);
    this.messageService.getInbox(this.inboxPage(), 10).subscribe({
      next: (response) => {
        this.inbox.set(response.content);
        this.inboxTotalPages.set(response.totalPages);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error loading inbox:', err);
        this.loading.set(false);
      }
    });
  }

  loadSent(): void {
    this.loading.set(true);
    this.messageService.getSent(this.sentPage(), 10).subscribe({
      next: (response) => {
        this.sent.set(response.content);
        this.sentTotalPages.set(response.totalPages);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error loading sent:', err);
        this.loading.set(false);
      }
    });
  }

  loadUnreadCount(): void {
    this.messageService.getUnreadCount().subscribe({
      next: (response) => {
        // Mettre à jour le badge si nécessaire
      },
      error: (err) => console.error('Error loading unread count:', err)
    });
  }

  // IMPLÉMENTATION DE LOAD USERS
  loadUsers(): void {
    this.loadingUsers.set(true);
    const url = `${environment.apiUrl}/users?page=0&size=100`; // Charge les 100 premiers utilisateurs
    
    this.http.get<any>(url).subscribe({
      next: (response) => {
        this.users.set(response.content || []);
        this.loadingUsers.set(false);
      },
      error: (err) => {
        console.error('Error loading users:', err);
        this.loadingUsers.set(false);
      }
    });
  }

  // Méthode pour obtenir les initiales en toute sécurité
  getInitials(name: string | undefined): string {
    if (!name || name.trim() === '') return '?';
    
    const parts = name.trim().split(' ');
    if (parts.length >= 2) {
      const firstInitial = parts[0][0] || '';
      const lastInitial = parts[parts.length - 1][0] || '';
      return (firstInitial + lastInitial).toUpperCase();
    }
    return name.substring(0, 2).toUpperCase() || '?';
  }

  selectMessage(message: Message): void {
    this.selectedMessage.set(message);
    if (!message.isRead && message.receiverId === this.authStore.userId()) {
      this.messageService.markAsRead(message.messageUuid).subscribe();
    }
  }

  sendReply(): void {
    const message = this.selectedMessage();
    if (!message || !this.replyContent()) return;

    const request: SendMessageRequest = {
      receiverId: message.senderId,
      subject: `Re: ${message.subject || ''}`,
      content: this.replyContent(),
      priority: 'NORMAL',
      parentMessageUuid: message.messageUuid
    };

    this.messageService.sendMessage(request).subscribe({
      next: () => {
        this.replyContent.set('');
        this.loadInbox();
        this.selectedMessage.set(null);
      },
      error: (err) => console.error('Error sending reply:', err)
    });
  }

  sendNewMessage(): void {
    const msg = this.newMessage();
    if (!msg.receiverId || !msg.content) return;

    const request: SendMessageRequest = {
      receiverId: msg.receiverId,
      subject: msg.subject,
      content: msg.content,
      priority: msg.priority
    };

    this.messageService.sendMessage(request).subscribe({
      next: () => {
        this.currentTab.set('sent');
        this.loadSent();
        this.resetNewMessage();
      },
      error: (err) => console.error('Error sending message:', err)
    });
  }

  resetNewMessage(): void {
    this.newMessage.set({
      receiverId: null,
      subject: '',
      content: '',
      priority: 'NORMAL'
    });
  }

  formatDate(date: string): string {
    if (!date) return '';
    return new Date(date).toLocaleString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  getPriorityClass(priority: string): string {
    const classes: Record<string, string> = {
      'LOW': 'bg-gray-100 text-gray-700',
      'NORMAL': 'bg-blue-100 text-blue-700',
      'HIGH': 'bg-orange-100 text-orange-700',
      'URGENT': 'bg-red-100 text-red-700'
    };
    return classes[priority] || classes['NORMAL'];
  }

  onInboxPageChange(page: number): void {
    this.inboxPage.set(page);
    this.loadInbox();
  }

  onSentPageChange(page: number): void {
    this.sentPage.set(page);
    this.loadSent();
  }
}