import { Component, OnInit, inject, signal, OnDestroy, computed, effect } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { MessageService } from '../../../../core/services/message.service';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { AuthStore } from '../../../../core/store/auth.store';
import { Message, SendMessageRequest } from '../../../../core/models/message.model';
import { Subscription } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import { RapportService } from '../../../../core/services/rapport.service';
import { Rapport, GenerateRapportRequest, RapportRecipient } from '../../../../core/models/rapport.model';

export interface User {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  roleId: number;
  roleName: string;
  managerId?: number;
  managerName?: string;
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
  private rapportService = inject(RapportService);
  authStore = inject(AuthStore);

  // State
  inbox = signal<Message[]>([]);
  sent = signal<Message[]>([]);
  unreadCount = signal(0);
  validationUnreadCount = signal(0);
  reportingUnreadCount = signal(0);
  currentTab = signal<'inbox' | 'sent' | 'compose' | 'validation' | 'archived' | 'reporting'>('inbox');
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

  // Multi-selection
  selectedMessages = signal<Set<string>>(new Set());

  // Pagination
  inboxPage = signal(0);
  inboxTotalPages = signal(0);
  sentPage = signal(0);
  sentTotalPages = signal(0);
  archived = signal<Message[]>([]);
  archivedPage = signal(0);
  archivedTotalPages = signal(0);

  // Search
  searchQuery = signal('');

  // Reporting state
  receivedRapports = signal<Rapport[]>([]);
  myRapports = signal<Rapport[]>([]);
  selectedRapport = signal<Rapport | null>(null);
  reportingSubTab = signal<'received' | 'mine'>('received');
  showGenerateReportForm = signal(false);
  showReportModels = signal(true);

  reportForm = signal<GenerateRapportRequest>({
    title: '',
    type: 'WEEKLY',
    description: '',
    recipientIds: []
  });

  filteredInbox = computed(() => {
    const q = this.searchQuery().toLowerCase();
    return this.inbox().filter(m =>
      m.senderName.toLowerCase().includes(q) ||
      m.subject.toLowerCase().includes(q) ||
      m.content.toLowerCase().includes(q)
    );
  });

  filteredSent = computed(() => {
    const q = this.searchQuery().toLowerCase();
    return this.sent().filter(m =>
      m.receiverName.toLowerCase().includes(q) ||
      m.subject.toLowerCase().includes(q) ||
      m.content.toLowerCase().includes(q)
    );
  });

  currentReportingList = computed(() =>
    this.reportingSubTab() === 'received' ? this.receivedRapports() : this.myRapports()
  );

  // Mark report as read when selected
  private markEffect = effect(() => {
    const rapport = this.selectedRapport();
    const currentUser = this.authStore.user();

    // Only mark as read if I am a recipient and it's not already read
    if (rapport && currentUser && this.reportingSubTab() === 'received' && !rapport.isRead) {
      this.rapportService.markAsRead(rapport.rapportUuid).subscribe({
        next: () => {
          // Update local state to show it's read in the list
          this.receivedRapports.update(list =>
            list.map(r => r.rapportUuid === rapport.rapportUuid ? { ...r, isRead: true } : r)
          );

          // Also update the selected rapport object itself
          const updatedRecipients = rapport.recipients?.map(dest =>
            dest.recipientId === this.authStore.userId() ? { ...dest, isRead: true, readAt: new Date().toISOString() } : dest
          );
          this.selectedRapport.update(current => current ? { ...current, isRead: true, recipients: updatedRecipients } : null);

          // Decrement the global badge
          this.reportingUnreadCount.update(c => Math.max(0, c - 1));
        }
      });
    }
  });

  toggleRecipient(userId: number): void {
    this.selectedRecipientIds.update(current =>
      current.includes(userId)
        ? current.filter(id => id !== userId)
        : [...current, userId]
    );
  }

  openShareModal(rapport: Rapport): void {
    this.rapportToShare.set(rapport);
    this.shareRecipientIds.set([]); // Reset selection
    this.isShareModalOpen.set(true);
  }

  closeShareModal(): void {
    this.isShareModalOpen.set(false);
    this.rapportToShare.set(null);
    this.shareRecipientIds.set([]);
  }

  toggleShareRecipient(userId: number): void {
    this.shareRecipientIds.update(current =>
      current.includes(userId)
        ? current.filter(id => id !== userId)
        : [...current, userId]
    );
  }

  submitShare(): void {
    const rapport = this.rapportToShare();
    const recipientIds = this.shareRecipientIds();

    if (!rapport || recipientIds.length === 0) return;

    this.isSharing.set(true);

    this.rapportService.shareRapport(rapport.rapportUuid, recipientIds).subscribe({
      next: (updatedRapport) => {
        // Update the selected rapport if it's the one we just shared
        if (this.selectedRapport()?.rapportUuid === updatedRapport.rapportUuid) {
          this.selectedRapport.set(updatedRapport);
        }

        // Update it in the generated list
        this.myRapports.update((list: Rapport[]) =>
          list.map((r: Rapport) => r.rapportUuid === updatedRapport.rapportUuid ? updatedRapport : r)
        );

        this.closeShareModal();
        this.isSharing.set(false);
      },
      error: (err) => {
        console.error('Failed to share rapport', err);
        this.isSharing.set(false);
      }
    });
  }

  anyRead(recipients: any[] | undefined): boolean {
    if (!recipients || recipients.length === 0) return false;
    return recipients.some(r => r.isRead === true || String(r.isRead) === 'true');
  }

  filteredArchived = computed(() => {
    const q = this.searchQuery().toLowerCase();
    return this.archived().filter(m =>
      m.senderName.toLowerCase().includes(q) ||
      m.subject.toLowerCase().includes(q) ||
      m.content.toLowerCase().includes(q)
    );
  });

  // New message
  newMessage = signal<{
    receiverId: number | null;
    subject: string;
    content: string;
  }>({ receiverId: null, subject: '', content: '' });

  users = signal<User[]>([]);
  selectedRecipientIds = signal<number[]>([]);
  loadingUsers = signal(false);

  // Share modal state
  isShareModalOpen = signal(false);
  rapportToShare = signal<Rapport | null>(null);
  shareRecipientIds = signal<number[]>([]);
  isSharing = signal(false);

  availableManagers = signal<User[]>([]);
  analystManager = signal<User | null>(null);

  isManager = computed(() => this.authStore.isManager());
  isAnalyst = computed(() => this.authStore.isAnalyst());
  currentUser = computed(() => this.authStore.user());
  currentUserId = computed(() => this.authStore.userId());
  currentUserManagerId = computed(() => {
    const user = this.currentUser();
    return (user as any)?.managerId || null;
  });

  getAvailableShareUsers = computed(() => {
    const allUsers = this.filteredUsersForReporting();
    const rapport = this.rapportToShare();
    if (!rapport) return allUsers;

    // Extract existing recipient IDs (plus the owner)
    const existingIds = new Set(rapport.recipients?.map(r => r.recipientId) || []);
    existingIds.add(rapport.generatedById);

    return allUsers.filter(u => !existingIds.has(u.id));
  });

  filteredUsersForReporting = computed(() => {
    let list = this.users();
    const currentUserId = this.authStore.userId();

    if (this.isAnalyst()) {
      const managerId = this.currentUserManagerId();
      list = list.filter(u =>
        u.roleName === 'ADMIN' ||
        (u.roleName === 'MANAGER' && u.id === managerId) ||
        (u.roleName === 'ANALYST' && u.managerId === managerId)
      );
    } else if (this.isManager()) {
      list = list.filter(u =>
        u.roleName === 'ADMIN' ||
        u.roleName === 'MANAGER'
      );
    }

    const roleOrder: Record<string, number> = {
      'ADMIN': 1,
      'MANAGER': 2,
      'ANALYST': 3
    };

    return [...list]
      .filter(u => u.id !== currentUserId)
      .sort((a, b) => {
        const orderA = roleOrder[a.roleName] || 99;
        const orderB = roleOrder[b.roleName] || 99;
        if (orderA !== orderB) return orderA - orderB;
        const nameA = `${a.firstName} ${a.lastName}`.toLowerCase();
        const nameB = `${b.firstName} ${b.lastName}`.toLowerCase();
        return nameA.localeCompare(nameB);
      });
  });

  allManagers = signal<User[]>([]);
  private wsSubscription: Subscription;

  validationComment = signal('');
  showRejectionWarning = signal(false);
  isValidating = signal(false);

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
      if ((event as any)?.type === 'NEW_REPORT') {
        this.loadReceivedRapports();
        this.loadUnreadCount();
      }
      if (event?.type === 'UNREAD_COUNT') {
        this.loadUnreadCount();
      }
    });
  }

  ngOnInit(): void {
    this.wsService.connect();
    this.loadInbox();
    this.loadUnreadCount();
    this.loadUsers();

    this.route.queryParams.subscribe(params => {
      if (params['tab'] === 'validation') {
        this.currentTab.set('validation');
        this.loadValidationMessages();
        if (params['modelUuid']) {
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
      }
      if (params['tab'] === 'reporting') {
        this.currentTab.set('reporting');
        this.loadReporting();
        if (params['rapportUuid']) {
          (this as any).loadRapportDetailsByUuid(params['rapportUuid']);
        }
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
  switchTab(tab: 'inbox' | 'sent' | 'compose' | 'validation' | 'archived' | 'reporting'): void {
    this.currentTab.set(tab);
    this.selectedMessage.set(null);
    this.selectedRapport.set(null);
    this.clearSelection();
    if (tab === 'sent') this.loadSent();
    if (tab === 'inbox') this.loadInbox();
    if (tab === 'archived') this.loadArchived();
    if (tab === 'reporting') this.loadReporting();
    if (tab === 'validation') {
      if (!this.validationModel()) {
        this.loadValidationMessages();
      } else {
        this.markValidationAsRead();
      }
    } else {
      this.validationModel.set(null);
    }
  }

  loadRapportDetailsByUuid(uuid: string): void {
    this.loading.set(true);
    this.rapportService.getRapport(uuid).subscribe({
      next: (rapport) => {
        this.selectedRapport.set(rapport);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error loading rapport by uuid:', err);
        this.loading.set(false);
      }
    });
  }

  private markValidationAsRead(): void {
    const unreadUuids = this.validationMessages()
      .filter(m => !m.isRead && (m.receiverEmail === this.authStore.userEmail() || m.receiverId === this.authStore.userId()))
      .map(m => m.messageUuid);

    if (unreadUuids.length > 0) {
      unreadUuids.forEach(uuid => {
        this.messageService.markAsRead(uuid).subscribe();
      });
      this.validationMessages.update(msgs =>
        msgs.map(m => unreadUuids.includes(m.messageUuid) ? { ...m, isRead: true } : m)
      );
      this.validationUnreadCount.set(0);
    }
  }

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

  onSearch(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.searchQuery.set(value);
  }

  toggleSelectAll(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    if (checked) {
      let allUuids: string[] = [];
      if (this.currentTab() === 'inbox') {
        allUuids = this.filteredInbox().map(m => m.messageUuid);
      } else if (this.currentTab() === 'sent') {
        allUuids = this.filteredSent().map(m => m.messageUuid);
      } else if (this.currentTab() === 'archived') {
        allUuids = this.filteredArchived().map(m => m.messageUuid);
      }
      this.selectedMessages.set(new Set(allUuids));
    } else {
      this.selectedMessages.set(new Set());
    }
  }

  isAllSelected(): boolean {
    let list: Message[] = [];
    if (this.currentTab() === 'inbox') list = this.filteredInbox();
    else if (this.currentTab() === 'sent') list = this.filteredSent();
    else if (this.currentTab() === 'archived') list = this.filteredArchived();

    return list.length > 0 && this.selectedMessages().size === list.length;
  }

  isSomeSelected(): boolean {
    let list: Message[] = [];
    if (this.currentTab() === 'inbox') list = this.filteredInbox();
    else if (this.currentTab() === 'sent') list = this.filteredSent();
    else if (this.currentTab() === 'archived') list = this.filteredArchived();

    return this.selectedMessages().size > 0 && this.selectedMessages().size < list.length;
  }

  clearSelection(): void {
    this.selectedMessages.set(new Set());
  }

  toggleSidebar(): void {
    this.selectedMessage.set(null);
    this.showReplyInput = false;
    this.replyContent.set('');
  }

  deleteSelected(): void {
    if (this.selectedMessages().size === 0) return;
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
      this.messageService.restoreMessages(uuids).subscribe({
        next: () => {
          this.archived.update(msgs => msgs.filter(m => !this.selectedMessages().has(m.messageUuid)));
          this.clearSelection();
          this.selectedMessage.set(null);
          this.loadInbox();
          this.loadUnreadCount();
        },
        error: (err) => console.error(err)
      });
    } else {
      this.messageService.archiveMessages(uuids).subscribe({
        next: () => {
          if (this.currentTab() === 'inbox') {
            this.inbox.update(msgs => msgs.filter(m => !this.selectedMessages().has(m.messageUuid)));
          } else if (this.currentTab() === 'sent') {
            this.sent.update(msgs => msgs.filter(m => !this.selectedMessages().has(m.messageUuid)));
          }
          this.clearSelection();
          this.selectedMessage.set(null);
          this.loadUnreadCount();
        },
        error: (err) => console.error(err)
      });
    }
  }

  toggleReadSelected(): void {
    if (this.selectedMessages().size === 0) return;
    const uuids = Array.from(this.selectedMessages());
    const selectedList = this.inbox().filter(m => uuids.includes(m.messageUuid));
    const allRead = selectedList.every(m => m.isRead);

    if (allRead) {
      this.inbox.update(msgs => msgs.map(m =>
        uuids.includes(m.messageUuid) ? { ...m, isRead: false } : m
      ));
    } else {
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

    this.messageService.getValidationMessages(0, 100).subscribe(response => {
      const validationMsgs: Message[] = response.content;
      const unread = validationMsgs.filter(m => !m.isRead).length;
      this.validationUnreadCount.set(unread);
    });

    this.rapportService.getUnreadCount().subscribe(res => {
      this.reportingUnreadCount.set(res.count);
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
      next: (response) => {
        const allUsers = response.content || [];
        this.users.set(allUsers);

        const managersList = allUsers.filter((u: User) => u.roleId === 2 && u.isActive);
        this.allManagers.set(managersList);

        if (this.isAnalyst()) {
          const managerId = this.currentUserManagerId();
          if (managerId) {
            const manager = managersList.find((m: User) => m.id === managerId);
            if (manager) {
              this.analystManager.set(manager);
              this.availableManagers.set([manager]);
            } else {
              this.availableManagers.set([]);
            }
          } else {
            this.availableManagers.set(managersList);
          }
        } else {
          this.availableManagers.set(managersList);
        }

        this.loadingUsers.set(false);
      },
      error: (err) => {
        console.error(err);
        this.loadingUsers.set(false);
      }
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
    this.selectedModelComment.set(null);

    if (msg.messageType === 'VALIDATION') {
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
    }

    if (!msg.isRead && (msg.receiverEmail === this.authStore.userEmail() || msg.receiverId === this.authStore.userId())) {
      this.validationMessages.update(msgs =>
        msgs.map(m => m.messageUuid === msg.messageUuid ? { ...m, isRead: true } : m)
      );
      this.validationUnreadCount.update(c => Math.max(0, c - 1));

      this.messageService.markAsRead(msg.messageUuid).subscribe(() => {
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
      analystId: this.currentUserId(),
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
        this.newMessage.set({ receiverId: null, subject: '', content: '' });
      },
      error: (err) => { console.error(err); this.loading.set(false); }
    });
  }

  // ─── Message actions ──────────────────────────────────────────
  selectMessage(message: Message): void {
    this.selectedMessage.set(message);
    this.selectedModelStatus.set(null);
    this.selectedModelComment.set(null);
    this.showReplyInput = false;
    this.replyContent.set('');

    if (message.messageType === 'VALIDATION') {
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
      },
      error: (err) => {
        console.error('Error rejecting model:', err);
        this.loading.set(false);
      }
    });
  }

  isValidationRequest(message: any): boolean {
    if (!message || !message.content) return false;
    // Only attempt JSON parse if it's a VALIDATION message
    if (message.messageType !== 'VALIDATION') return false;
    try {
      let content = message.content.trim();
      const data = JSON.parse(content);
      return data && data.modelUuid !== undefined;
    } catch (e) { return false; }
  }

  getMessagePreview(message: any): string {
    if (this.isValidationRequest(message)) {
      const data = this.parseValidationContent(message.content);
      return `Demande de validation : ${data?.modelName || 'Modèle'}`;
    }
    return message.content;
  }

  handleValidationAction(status: 'Validated' | 'Rejected'): void {
    const message = this.selectedMessage() || this.selectedValidation();
    if (!message || message.messageType !== 'VALIDATION') return;
    const details = this.parseValidationContent(message.content);
    if (!details || !details.modelUuid) return;
    this.isValidating.set(true);
    if (status === 'Rejected' && !this.validationComment()) {
      this.showRejectionWarning.set(true);
      this.isValidating.set(false);
      return;
    }
    this.showRejectionWarning.set(false);
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
    try { return content ? JSON.parse(content) : null; }
    catch (e) { return null; }
  }

  // ─── Reporting ───────────────────────────────────────────────
  loadReporting(): void {
    this.loadReceivedRapports();
    this.loadMyRapports();
    this.loadUnreadCount();
  }

  loadReceivedRapports(): void {
    this.loading.set(true);
    this.rapportService.getReceivedRapports(0, 50).subscribe({
      next: (res) => {
        this.receivedRapports.set(res.content);
        this.loading.set(false);
      },
      error: (err) => { console.error(err); this.loading.set(false); }
    });
  }

  loadMyRapports(): void {
    this.loading.set(true);
    this.rapportService.getGeneratedRapports(0, 50).subscribe({
      next: (res) => {
        this.myRapports.set(res.content);
        this.loading.set(false);
      },
      error: (err) => { console.error(err); this.loading.set(false); }
    });
  }

  selectRapport(rapport: Rapport): void {
    // Set immediate selection for UI responsiveness
    this.selectedRapport.set(rapport);

    // Fetch latest details to have up-to-date read receipts (especially for owner)
    this.rapportService.getRapport(rapport.rapportUuid).subscribe({
      next: (latest) => {
        this.selectedRapport.set(latest);
      },
      error: (err) => console.error('Erreur lors du chargement des détails du rapport:', err)
    });
  }

  toggleGenerateReportForm(): void {
    this.showGenerateReportForm.set(!this.showGenerateReportForm());
    if (this.showGenerateReportForm()) {
      this.selectedRapport.set(null);
      this.reportForm.set({
        title: `Rapport d'activité - ${new Date().toLocaleDateString()}`,
        type: 'WEEKLY',
        description: '',
        recipientIds: []
      });
    }
  }

  parseDatasetDate(date: any): string | null {
    if (!date) return null;
    if (Array.isArray(date)) {
      // Spring Boot LocalDateTime array format: [YYYY, MM, DD, HH, mm, ss]
      if (date.length >= 5) {
        const d = new Date(date[0], date[1] - 1, date[2], date[3], date[4], date[5] || 0);
        return d.toISOString();
      } else if (date.length >= 3) {
        const d = new Date(date[0], date[1] - 1, date[2]);
        return d.toISOString();
      }
    }
    return date; // If it's already a string, return as is
  }

  getDatasetsByStatuses(datasets: any[], statuses: string[]): any[] {
    if (!datasets || !Array.isArray(datasets)) return [];
    let filtered = datasets.filter(d => statuses.includes(d.status));

    if (statuses.includes('PROCESSED')) {
      return filtered.filter(d => d.datasetName.toLowerCase().includes('enriched'));
    }

    return filtered;
  }

  getFilteredDatasetsCount(datasets: any[]): number {
    if (!datasets || !Array.isArray(datasets)) return 0;
    // On compte les QUALIFIED, PROCESSED (filtrés) et les autres statuts
    const qualified = this.getDatasetsByStatuses(datasets, ['QUALIFIED']).length;
    const processed = this.getDatasetsByStatuses(datasets, ['PROCESSED']).length;
    const others = this.getDatasetsByStatuses(datasets, ['CLEANED', 'INACTIF', 'PENDING']).length;
    return qualified + processed + others;
  }

  submitGenerateReport(): void {
    if (!this.reportForm().title) return;
    this.loading.set(true);
    this.rapportService.generateRapport(this.reportForm()).subscribe({
      next: () => {
        this.showGenerateReportForm.set(false);
        this.reportingSubTab.set('mine');
        this.loadMyRapports();
        this.loading.set(false);
      },
      error: (err) => {
        console.error(err);
        this.loading.set(false);
      }
    });
  }

  setReportingSubTab(tab: 'received' | 'mine'): void {
    this.reportingSubTab.set(tab);
    this.selectedRapport.set(null);
  }

  getRiskColor(risk: string): string {
    switch (risk) {
      case 'CRITICAL': return '#ef4444';
      case 'HIGH': return '#f97316';
      case 'MEDIUM': return '#eab308';
      case 'LOW': return '#22c55e';
      default: return '#94a3b8';
    }
  }

  translateRisk(risk: string): string {
    const mapping: Record<string, string> = {
      'CRITICAL': 'Critique',
      'HIGH': 'Élevé',
      'MEDIUM': 'Moyen',
      'LOW': 'Faible'
    };
    return mapping[risk] || risk;
  }

  downloadPdf(rapport: Rapport) {
    const printElement = document.getElementById('printable-report-container');

    if (printElement) {
      const printContainer = document.createElement('div');
      printContainer.innerHTML = printElement.innerHTML;

      const style = document.createElement('style');
      style.innerHTML = `
        @media print {
          html {
            font-size: 70% !important;
          }
          body {
            background-color: white !important;
          }

          #printable-report-container, #report-scroll-area { 
            overflow: visible !important; 
            height: auto !important; 
            max-height: none !important; 
          }
          
          * { 
            -webkit-print-color-adjust: exact !important; 
            color-adjust: exact !important;
            print-color-adjust: exact !important;
          }
          
          .no-export { display: none !important; }
          i-feather[name="file-text"] { display: none !important; visibility: hidden !important; }
        }
      `;
      printContainer.appendChild(style);

      printContainer.style.position = 'relative';
      printContainer.style.width = '100%';
      printContainer.style.height = 'auto';
      printContainer.style.overflow = 'visible';
      printContainer.style.backgroundColor = 'white';

      const originalChildren = Array.from(document.body.children);
      originalChildren.forEach((child: any) => {
        if (child.style) {
          child.dataset['originalDisplay'] = child.style.display;
          child.style.display = 'none';
        }
      });

      // Sauvegarder et forcer les styles de html/body
      const htmlStyle = document.documentElement.style.cssText;
      const bodyStyle = document.body.style.cssText;
      document.documentElement.style.setProperty('height', 'auto', 'important');
      document.documentElement.style.setProperty('overflow', 'visible', 'important');
      document.body.style.setProperty('height', 'auto', 'important');
      document.body.style.setProperty('overflow', 'visible', 'important');

      document.body.appendChild(printContainer);

      const originalTitle = document.title;
      document.title = rapport.title ? rapport.title : 'Rapport_Activite';

      window.print();

      // Restaurer le titre original 
      document.title = originalTitle;

      // Nettoyage après impression
      document.body.removeChild(printContainer);
      originalChildren.forEach((child: any) => {
        if (child.style) {
          child.style.display = child.dataset['originalDisplay'] || '';
        }
      });

      // Restaurer les styles globaux
      document.documentElement.style.cssText = htmlStyle;
      document.body.style.cssText = bodyStyle;

    } else {
      window.print();
    }
  }
}