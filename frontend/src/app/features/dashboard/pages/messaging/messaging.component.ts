import { Component, OnInit, inject, signal, OnDestroy, computed } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
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
  private route = inject(ActivatedRoute);
  authStore = inject(AuthStore);

  // State
  inbox = signal<Message[]>([]);
  sent = signal<Message[]>([]);
  unreadCount = signal(0); // General Inbox
  validationUnreadCount = signal(0); // Validation requests
  currentTab = signal<'inbox' | 'sent' | 'compose' | 'validation' | 'archived'>('inbox');
  selectedMessage = signal<Message | null>(null);
  replyContent = signal('');
  loading = signal(false);
  ribbonCollapsed = signal<boolean>(false);
  showReplyInput = false;

  // Validation state
  validationModel = signal<any>(null);
  validationMessages = signal<Message[]>([]);
  selectedValidation = signal<Message | null>(null);
  selectedModelStatus = signal<string | null>(null);
  selectedModelComment = signal<string | null>(null);

  // Multi-selection (Set of messageUuids)
  selectedMessages = signal<Set<string>>(new Set());

  // Pagination
  inboxPage = signal(0);
  inboxTotalPages = signal(0);
  sentPage = signal(0);
  sentTotalPages = signal(0);
  archived = signal<Message[]>([]);
  archivedPage = signal(0);
  archivedTotalPages = signal(0);

  // New message
  newMessage = signal<{
    receiverId: number | null;
    subject: string;
    content: string;
  }>({ receiverId: null, subject: '', content: '' });

  users = signal<User[]>([]);
  loadingUsers = signal(false);

  // Mapping sur les managers
  managers = computed(() => this.users().filter(u => u.roleId === 2));
  isManager = computed(() => this.authStore.isManager());

  private wsSubscription: Subscription;

  constructor() {
    this.wsSubscription = this.wsService.messages$.subscribe(event => {
      if (event?.type === 'NEW_MESSAGE') {
        const msg: Message = event.payload;
        if (msg.messageType === 'VALIDATION') {
          this.loadValidationMessages();
          this.loadUnreadCount();
        } else {
          this.loadInbox();
          this.loadUnreadCount();
        }
      }
      if (event?.type === 'UNREAD_COUNT') {
        // Rediriger vers loadUnreadCount pour recalculer les deux compteurs
        this.loadUnreadCount();
      }
    });
  }

  ngOnInit(): void {
    this.wsService.connect();
    this.loadInbox();
    this.loadUnreadCount();
    this.loadUsers();

    // Check query params for model validation request
    this.route.queryParams.subscribe(params => {
      if (params['tab'] === 'validation' && params['modelUuid']) {
        this.currentTab.set('validation');
        this.validationModel.set({
          modelName: params['modelName'],
          modelUuid: params['modelUuid'],
          version: params['version'],
          auc: params['auc'],
          precision: params['precision'],
          recall: params['recall'],
          f1Score: params['f1Score'],
          modelType: params['modelType'],
          createdAt: params['createdAt']
        });
        this.newMessage.set({
          ...this.newMessage(),
          subject: `Demande de validation de modèle "${params['modelName']}"`
        });
      }
    });

    if (this.currentTab() === 'validation' && !this.validationModel()) {
      this.loadValidationMessages();
    }
  }

  ngOnDestroy(): void {
    this.wsSubscription?.unsubscribe();
    this.wsService.disconnect();
  }

  // ─── Navigation ───────────────────────────────────────────────
  switchTab(tab: 'inbox' | 'sent' | 'compose' | 'validation' | 'archived'): void {
    this.currentTab.set(tab);
    this.selectedMessage.set(null);
    this.clearSelection();
    if (tab === 'sent') this.loadSent();
    if (tab === 'inbox') this.loadInbox();
    if (tab === 'archived') this.loadArchived();
    if (tab === 'validation') {
      if (!this.validationModel()) {
        this.loadValidationMessages();
      } else {
        this.markValidationAsRead();
      }
    } else {
      // Clear validation model if switching to other tabs
      this.validationModel.set(null);
    }
  }

  private markValidationAsRead(): void {
    // Collect all unread validation messages and mark them as read
    const unreadUuids = this.validationMessages()
      .filter(m => !m.isRead && (m.receiverEmail === this.authStore.userEmail() || m.receiverId === this.authStore.userId()))
      .map(m => m.messageUuid);

    if (unreadUuids.length > 0) {
      unreadUuids.forEach(uuid => {
        this.messageService.markAsRead(uuid).subscribe();
      });
      // Update local state
      this.validationMessages.update(msgs =>
        msgs.map(m => unreadUuids.includes(m.messageUuid) ? { ...m, isRead: true } : m)
      );
      this.validationUnreadCount.set(0);
    }
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

  toggleSidebar(): void {
    // Désormais ce bouton sert uniquement à FERMER la conversation ouverte
    this.selectedMessage.set(null);
    this.showReplyInput = false;
    this.replyContent.set('');
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
    const uuids = Array.from(this.selectedMessages());

    if (this.currentTab() === 'archived') {
      // Restaurer
      this.messageService.restoreMessages(uuids).subscribe({
        next: () => {
          this.archived.update(msgs => msgs.filter(m => !this.selectedMessages().has(m.messageUuid)));
          this.clearSelection();
          this.selectedMessage.set(null);
          this.loadInbox();
          this.loadUnreadCount();
        },
        error: (err) => console.error('Erreur lors de la restauration:', err)
      });
    } else {
      // Archiver
      this.messageService.archiveMessages(uuids).subscribe({
        next: () => {
          // Retirer de la liste courante
          if (this.currentTab() === 'inbox') {
            this.inbox.update(msgs => msgs.filter(m => !this.selectedMessages().has(m.messageUuid)));
          } else if (this.currentTab() === 'sent') {
            this.sent.update(msgs => msgs.filter(m => !this.selectedMessages().has(m.messageUuid)));
          }
          this.clearSelection();
          this.selectedMessage.set(null);
          this.loadUnreadCount();
        },
        error: (err) => console.error('Erreur lors de l\'archivage:', err)
      });
    }
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
        // Filter out VALIDATION messages from inbox
        this.inbox.set(response.content.filter((m: Message) => m.messageType !== 'VALIDATION'));
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
        // Analysts SHOULD see their sent validation requests in the Sent tab
        this.sent.set(response.content);
        this.sentTotalPages.set(response.totalPages);
        this.loading.set(false);
      },
      error: (err) => { console.error(err); this.loading.set(false); }
    });
  }

  loadArchived(): void {
    this.loading.set(true);
    this.messageService.getArchived(this.archivedPage(), 10).subscribe({
      next: (response) => {
        this.archived.set(response.content);
        this.archivedTotalPages.set(response.totalPages);
        this.loading.set(false);
      },
      error: (err) => { console.error(err); this.loading.set(false); }
    });
  }

  loadUnreadCount(): void {
    this.messageService.getInbox(0, 100).subscribe(response => {
      const inboxMessages: Message[] = response.content;
      const filteredInbox = inboxMessages.filter(m => m.messageType !== 'VALIDATION');
      const unread = filteredInbox.filter(m => !m.isRead).length;
      this.unreadCount.set(unread);
    });

    // Fetch validation messages and count unread
    this.messageService.getValidationMessages(0, 100).subscribe(response => {
      const validationMsgs: Message[] = response.content;
      const unread = validationMsgs.filter(m => !m.isRead).length;
      this.validationUnreadCount.set(unread);
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
    if (!this.authStore.isAdmin()) return;
    this.loadingUsers.set(true);
    this.http.get<any>(`${environment.apiUrl}/users?page=0&size=100`).subscribe({
      next: (response) => { this.users.set(response.content || []); this.loadingUsers.set(false); },
      error: (err) => { console.error(err); this.loadingUsers.set(false); }
    });
  }

  loadValidationMessages(): void {
    this.loading.set(true);
    this.messageService.getValidationMessages(0, 50).subscribe({
      next: (response) => {
        this.validationMessages.set(response.content);
        this.loading.set(false);
        if (this.currentTab() === 'validation') {
          this.markValidationAsRead();
        }
      },
      error: (err) => { console.error(err); this.loading.set(false); }
    });
  }

  selectValidation(msg: Message): void {
    this.selectedValidation.set(msg);
    this.selectedModelStatus.set(null); 
    this.selectedModelComment.set(null); // Reset before fetch

    const details = this.parseValidationContent(msg.content);
    if (details && details.modelUuid) {
      this.http.get<any>(`${environment.apiUrl}/v1/fraud-detection/models/training/${details.modelUuid}/metrics`).subscribe({
        next: (response) => {
          this.selectedModelStatus.set(response.status);
          this.selectedModelComment.set(response.validationComment || response.comment);
        },
        error: (err) => console.error('Error fetching model status:', err)
      });
    }

    // Use email check as userId() may be 0 due to OIDC mapping
    if (!msg.isRead && (msg.receiverEmail === this.authStore.userEmail() || msg.receiverId === this.authStore.userId())) {
      // Mark as read immediately in local state for better UX
      this.validationMessages.update(msgs =>
        msgs.map(m => m.messageUuid === msg.messageUuid ? { ...m, isRead: true } : m)
      );
      this.validationUnreadCount.update(c => Math.max(0, c - 1));

      this.messageService.markAsRead(msg.messageUuid).subscribe(() => {
        // Refresh counts from server to stay in sync
        this.loadUnreadCount();
      });
    }
  }

  sendValidationRequest(): void {
    if (!this.newMessage().receiverId || !this.newMessage().subject) return;

    this.loading.set(true);
    const model = this.validationModel();
    const content = JSON.stringify({
      modelUuid: model.modelUuid,
      modelName: model.modelName,
      version: model.version,
      auc: model.auc,
      precision: model.precision,
      recall: model.recall,
      f1Score: model.f1Score,
      modelType: model.modelType,
      createdAt: model.createdAt,
      analystName: this.authStore.userName(),
      requestDate: new Date().toISOString()
    });

    const request: SendMessageRequest = {
      receiverId: this.newMessage().receiverId!,
      subject: this.newMessage().subject,
      content: content,
      messageType: 'VALIDATION'
    };

    this.messageService.sendMessage(request).subscribe({
      next: () => {
        this.loading.set(false);
        this.validationModel.set(null);
        this.switchTab('sent');
        // Reset compose fields
        this.newMessage.set({ receiverId: null, subject: '', content: '' });
      },
      error: (err) => {
        console.error(err);
        this.loading.set(false);
      }
    });
  }

  // ─── Message actions ──────────────────────────────────────────
  selectMessage(message: Message): void {
    this.selectedMessage.set(message);
    this.selectedModelStatus.set(null);
    this.selectedModelComment.set(null); // Reset before fetch
    this.showReplyInput = false;
    this.replyContent.set('');

    const details = this.parseValidationContent(message.content);
    if (details && details.modelUuid) {
      this.http.get<any>(`${environment.apiUrl}/v1/fraud-detection/models/training/${details.modelUuid}/metrics`).subscribe({
        next: (response) => {
          this.selectedModelStatus.set(response.status);
          this.selectedModelComment.set(response.validationComment || response.comment);
        },
        error: (err) => console.error('Error fetching model status:', err)
      });
    }

    if (!message.isRead && (message.receiverEmail === this.authStore.userEmail() || message.receiverId === this.authStore.userId())) {
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
      parentMessageUuid: message.messageUuid
    };
    this.messageService.sendMessage(request).subscribe({
      next: () => {
        this.replyContent.set('');
        this.showReplyInput = false;
        this.selectedMessage.set(null);
        this.loadSent();
      },
      error: (err) => console.error(err)
    });
  }

  sendNewMessage(): void {
    const msg = this.newMessage();
    if (!msg.receiverId || !msg.content) return;
    const request: SendMessageRequest = {
      receiverId: msg.receiverId,
      subject: msg.subject,
      content: msg.content
    };
    this.messageService.sendMessage(request).subscribe({
      next: () => { this.switchTab('sent'); this.resetNewMessage(); },
      error: (err) => console.error(err)
    });
  }

  resetNewMessage(): void {
    this.newMessage.set({ receiverId: null, subject: '', content: '' });
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

  onInboxPageChange(page: number): void { this.inboxPage.set(page); this.loadInbox(); }
  onSentPageChange(page: number): void { this.sentPage.set(page); this.loadSent(); }

  approveModel(msg: Message): void {
    const details = this.parseValidationContent(msg.content);
    if (!details || !details.modelUuid) return;

    this.loading.set(true);
    this.http.post(`${environment.apiUrl}/models/${details.modelUuid}/validate`, {}).subscribe({
      next: () => {
        this.loading.set(false);
        this.selectedValidation.set(null);
        this.loadValidationMessages();
      },
      error: (err) => {
        console.error('Error approving model:', err);
        this.loading.set(false);
      }
    });
  }

  rejectModel(msg: Message): void {
    const details = this.parseValidationContent(msg.content);
    if (!details || !details.modelUuid) return;

    this.loading.set(true);
    this.http.post(`${environment.apiUrl}/models/${details.modelUuid}/reject`, {}).subscribe({
      next: () => {
        this.loading.set(false);
        this.selectedValidation.set(null);
        this.loadValidationMessages();
        // Optional: Notify success
      },
      error: (err) => {
        console.error('Error rejecting model:', err);
        this.loading.set(false);
      }
    });
  }

  isValidationRequest(message: any): boolean {
    if (!message || !message.content) return false;
    try {
      let content = message.content.trim();
      const data = JSON.parse(content);
      return data && data.modelUuid !== undefined;
    } catch (e) {
      return false;
    }
  }

  getMessagePreview(message: any): string {
    if (this.isValidationRequest(message)) {
      const data = this.parseValidationContent(message.content);
      return `Demande de validation : ${data?.modelName || 'Modèle'}`;
    }
    return message.content;
  }

  validationComment = signal('');
  isValidating = signal(false);

  handleValidationAction(status: 'Validated' | 'Rejected'): void {
    const message = this.selectedMessage() || this.selectedValidation();
    if (!message) return;

    const details = this.parseValidationContent(message.content);
    if (!details || !details.modelUuid) {
      console.error('Invalid validation content: modelUuid missing');
      return;
    }

    this.isValidating.set(true);
    this.messageService.validateModel({
      modelUuid: details.modelUuid,
      status: status,
      comment: this.validationComment()
    }).subscribe({
      next: () => {
        this.isValidating.set(false);
        this.selectedModelStatus.set(status === 'Validated' ? 'Validated' : 'Rejected');
        this.selectedModelComment.set(this.validationComment());
        this.validationComment.set('');
        // We don't clear selection so user can see the "new" result card
        this.loadValidationMessages();
        this.loadUnreadCount();
      },
      error: (err) => {
        this.isValidating.set(false);
        console.error('Validation action failed', err);
      }
    });
  }

  parseValidationContent(content: string): any {
    try {
      if (!content) return null;
      return JSON.parse(content);
    } catch (e) {
      console.error('Error parsing validation content:', e);
      return null;
    }
  }
}