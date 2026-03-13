import { Component, OnInit, inject, signal, OnDestroy, computed } from '@angular/core';
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
  currentTab = signal<'inbox' | 'sent' | 'compose' | 'validation'>('inbox');
  selectedMessage = signal<Message | null>(null);
  replyContent = signal('');
  loading = signal(false);
  unreadCount = signal<number>(0);
  ribbonCollapsed = signal<boolean>(false);

  // Multi-selection (Set of messageUuids)
  selectedMessages = signal<Set<string>>(new Set());

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
  }>({ receiverId: null, subject: '', content: '', priority: 'NORMAL' });

  users = signal<User[]>([]);
  loadingUsers = signal(false);

  private wsSubscription: Subscription;

  constructor() {
    this.wsSubscription = this.wsService.messages$.subscribe(event => {
      if (event?.type === 'NEW_MESSAGE') {
        this.loadInbox();
        this.loadUnreadCount();
      }
      if (event?.type === 'UNREAD_COUNT') {
        this.unreadCount.set(event.payload);
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

  // ─── Navigation ───────────────────────────────────────────────
  switchTab(tab: 'inbox' | 'sent' | 'compose' | 'validation'): void {
    this.currentTab.set(tab);
    this.selectedMessage.set(null);
    this.clearSelection();
    if (tab === 'sent') this.loadSent();
    if (tab === 'inbox') this.loadInbox();
  }

  // ─── Selection helpers ────────────────────────────────────────
  isMessageSelected(uuid: string): boolean {
    return this.selectedMessages().has(uuid);
  }

  toggleMessageSelection(uuid: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const current = new Set(this.selectedMessages());
    if (checked) {
      current.add(uuid);
    } else {
      current.delete(uuid);
    }
    this.selectedMessages.set(current);
  }

  toggleSelectAll(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    if (checked) {
      const allUuids = this.currentTab() === 'inbox'
        ? this.inbox().map(m => m.messageUuid)
        : this.sent().map(m => m.messageUuid);
      this.selectedMessages.set(new Set(allUuids));
    } else {
      this.selectedMessages.set(new Set());
    }
  }

  isAllSelected(): boolean {
    const list = this.currentTab() === 'inbox' ? this.inbox() : this.sent();
    return list.length > 0 && this.selectedMessages().size === list.length;
  }

  isSomeSelected(): boolean {
    const list = this.currentTab() === 'inbox' ? this.inbox() : this.sent();
    return this.selectedMessages().size > 0 && this.selectedMessages().size < list.length;
  }

  clearSelection(): void {
    this.selectedMessages.set(new Set());
  }

  // ─── Ribbon actions ───────────────────────────────────────────
  deleteSelected(): void {
    if (this.selectedMessages().size === 0) return;
    // TODO: appeler l'API de suppression pour chaque UUID sélectionné
    // Exemple avec un endpoint delete (à implémenter côté backend) :
    // const uuids = Array.from(this.selectedMessages());
    // this.messageService.deleteMessages(uuids).subscribe(() => { ... });
    console.log('Supprimer:', Array.from(this.selectedMessages()));
    // Pour l'instant on retire de la liste locale
    if (this.currentTab() === 'inbox') {
      this.inbox.update(msgs => msgs.filter(m => !this.selectedMessages().has(m.messageUuid)));
    } else {
      this.sent.update(msgs => msgs.filter(m => !this.selectedMessages().has(m.messageUuid)));
    }
    this.clearSelection();
    this.selectedMessage.set(null);
  }

  archiveSelected(): void {
    if (this.selectedMessages().size === 0) return;
    // TODO: appeler l'API d'archivage
    console.log('Archiver:', Array.from(this.selectedMessages()));
    // Pour l'instant on retire de la liste locale
    if (this.currentTab() === 'inbox') {
      this.inbox.update(msgs => msgs.filter(m => !this.selectedMessages().has(m.messageUuid)));
    } else {
      this.sent.update(msgs => msgs.filter(m => !this.selectedMessages().has(m.messageUuid)));
    }
    this.clearSelection();
    this.selectedMessage.set(null);
  }

  toggleReadSelected(): void {
    if (this.selectedMessages().size === 0) return;
    const uuids = Array.from(this.selectedMessages());
    // Détermine si on marque comme lu ou non lu selon l'état majoritaire
    const selectedList = this.inbox().filter(m => uuids.includes(m.messageUuid));
    const allRead = selectedList.every(m => m.isRead);

    if (allRead) {
      // TODO: marquer comme non lu via API
      this.inbox.update(msgs => msgs.map(m =>
        uuids.includes(m.messageUuid) ? { ...m, isRead: false } : m
      ));
    } else {
      // Marquer comme lu
      uuids.forEach(uuid => {
        this.messageService.markAsRead(uuid).subscribe();
      });
      this.inbox.update(msgs => msgs.map(m =>
        uuids.includes(m.messageUuid) ? { ...m, isRead: true } : m
      ));
      this.unreadCount.update(c => Math.max(0, c - selectedList.filter(m => !m.isRead).length));
    }
    this.clearSelection();
  }

  // ─── Data loading ─────────────────────────────────────────────
  loadInbox(): void {
    this.loading.set(true);
    this.messageService.getInbox(this.inboxPage(), 10).subscribe({
      next: (response) => {
        this.inbox.set(response.content);
        this.inboxTotalPages.set(response.totalPages);
        this.loading.set(false);
      },
      error: (err) => { console.error(err); this.loading.set(false); }
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
      error: (err) => { console.error(err); this.loading.set(false); }
    });
  }

  loadUnreadCount(): void {
    this.messageService.getUnreadCount().subscribe({
      next: (response) => this.unreadCount.set(response.count ?? 0),
      error: (err) => console.error(err)
    });
  }

  markAllAsRead(): void {
    this.messageService.markAllAsRead().subscribe({
      next: () => {
        this.unreadCount.set(0);
        this.inbox.update(msgs => msgs.map(m => ({ ...m, isRead: true })));
      }
    });
  }

  loadUsers(): void {
    this.loadingUsers.set(true);
    this.http.get<any>(`${environment.apiUrl}/users?page=0&size=100`).subscribe({
      next: (response) => { this.users.set(response.content || []); this.loadingUsers.set(false); },
      error: (err) => { console.error(err); this.loadingUsers.set(false); }
    });
  }

  // ─── Message actions ──────────────────────────────────────────
  selectMessage(message: Message): void {
    this.selectedMessage.set(message);

    if (!message.isRead && message.receiverId === this.authStore.userId()) {
      this.messageService.markAsRead(message.messageUuid).subscribe({
        next: () => {
          this.inbox.update(msgs =>
            msgs.map(m =>
              m.messageUuid === message.messageUuid
                ? { ...m, isRead: true }
                : m
            )
          );

          this.selectedMessage.update(selected =>
            selected && selected.messageUuid === message.messageUuid
              ? { ...selected, isRead: true }
              : selected
          );

          this.unreadCount.update(c => Math.max(0, c - 1));
        },
        error: (err) => console.error('Erreur lors du marquage du message:', err)
      });
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
      next: () => { this.replyContent.set(''); this.selectedMessage.set(null); this.loadSent(); },
      error: (err) => console.error(err)
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
      next: () => { this.switchTab('sent'); this.resetNewMessage(); },
      error: (err) => console.error(err)
    });
  }

  resetNewMessage(): void {
    this.newMessage.set({ receiverId: null, subject: '', content: '', priority: 'NORMAL' });
  }

  // ─── Helpers ──────────────────────────────────────────────────
  getInitials(name: string | undefined): string {
    if (!name || name.trim() === '') return '?';
    const parts = name.trim().split(' ');
    if (parts.length >= 2) return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
    return name.substring(0, 2).toUpperCase() || '?';
  }

  formatDate(date: string): string {
    if (!date) return '';
    return new Date(date).toLocaleString('fr-FR', {
      day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit'
    });
  }

  formatDateShort(date: string): string {
    if (!date) return '';
    const d = new Date(date);
    const today = new Date();
    if (d.toDateString() === today.toDateString()) {
      return d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
    }
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' });
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

  onInboxPageChange(page: number): void { this.inboxPage.set(page); this.loadInbox(); }
  onSentPageChange(page: number): void { this.sentPage.set(page); this.loadSent(); }
}