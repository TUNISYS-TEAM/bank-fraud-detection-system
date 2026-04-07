import { Injectable } from '@angular/core';
import { Client, Message as StompMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { environment } from '../../../environments/environment';
import { BehaviorSubject, Observable } from 'rxjs';

export interface WebSocketMessage {
  type: 'NEW_MESSAGE' | 'MESSAGE_READ' | 'UNREAD_COUNT' | 'NOTIFICATION';
  payload: any;
  timestamp: string;
}

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private client: Client | null = null;
  private connected = new BehaviorSubject<boolean>(false);
  private unreadCount = new BehaviorSubject<number>(0);
  private messageSubject = new BehaviorSubject<WebSocketMessage | null>(null);
  private notificationSubject = new BehaviorSubject<any | null>(null);

  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private connectionInProgress = false;

  constructor() {
    this.ensureGlobalDefined();
    setTimeout(() => this.connect(), 1000);
  }

  private ensureGlobalDefined(): void {
    if (typeof window !== 'undefined' && typeof (window as any).global === 'undefined') {
      (window as any).global = window;
    }
  }

  connect(): void {
    if (this.connectionInProgress || (this.client && this.client.active)) {
      return;
    }
    
    this.connectionInProgress = true;
    this.ensureGlobalDefined();

    try {
      if (this.client) {
        try { this.client.deactivate(); } catch(e) {}
      }
      
      this.client = new Client({
        webSocketFactory: () => {
          this.ensureGlobalDefined();
          return new SockJS(`${environment.apiUrl}/ws`, null, {
            withCredentials: true
          } as any);
        },
        debug: (str) => {
          if (!environment.production) console.log('STOMP:', str);
        },
        reconnectDelay: 5000,
        heartbeatIncoming: 4000,
        heartbeatOutgoing: 4000,
      });

      this.client.onConnect = () => {
        this.connected.next(true);
        this.reconnectAttempts = 0;
        this.connectionInProgress = false;
        this.subscribeToUserQueues();
      };

      this.client.onDisconnect = () => {
        this.connected.next(false);
        this.connectionInProgress = false;
      };

      this.client.onStompError = (frame) => {
        this.connected.next(false);
        this.connectionInProgress = false;
      };

      this.client.onWebSocketError = (event) => {
        this.connected.next(false);
        this.connectionInProgress = false;
      };

      this.client.activate();
    } catch (error) {
      this.connectionInProgress = false;
    }
  }

  private subscribeToUserQueues(): void {
    if (!this.client || !this.client.connected) {
      return;
    }

    this.client.subscribe('/user/queue/notifications', (message: StompMessage) => {
      try {
        const notification = JSON.parse(message.body);
        this.notificationSubject.next(notification);
        this.messageSubject.next({
          type: 'NOTIFICATION',
          payload: notification,
          timestamp: new Date().toISOString()
        });
      } catch (error) {
        // Error handling without log
      }
    });

    this.client.subscribe('/user/queue/messages', (message: StompMessage) => {
      try {
        let payload: any;
        try { payload = JSON.parse(message.body); } catch { payload = message.body; }
        this.messageSubject.next({
          type: 'NEW_MESSAGE',
          payload,
          timestamp: new Date().toISOString()
        });
      } catch (error) {
        // Error handling without log
      }
    });

    this.client.subscribe('/user/queue/unread', (message: StompMessage) => {
      try {
        const count = parseInt(message.body, 10);
        if (!isNaN(count)) {
          this.unreadCount.next(count);
          this.messageSubject.next({
            type: 'UNREAD_COUNT',
            payload: count,
            timestamp: new Date().toISOString()
          });
        }
      } catch (error) {
      }
    });

    this.client.subscribe('/user/queue/read', (message: StompMessage) => {
      try {
        this.messageSubject.next({
          type: 'MESSAGE_READ',
          payload: message.body,
          timestamp: new Date().toISOString()
        });
      } catch (error) {
      }
    });
  }

  disconnect(): void {
    if (this.client && this.client.active) {
      try {
        this.client.deactivate();
      } catch (error) {
      }
    }
    this.connected.next(false);
    this.connectionInProgress = false;
  }

  sendMessage(destination: string, payload: any): void {
    if (!this.client || !this.client.connected) {
      return;
    }
    try {
      this.client.publish({
        destination: `/app/${destination}`,
        body: JSON.stringify(payload)
      });
    } catch (error) {
    }
  }

  markMessageAsRead(messageUuid: string): void {
    this.sendMessage('mark-read', { messageUuid });
  }

  requestUnreadCount(): void {
    this.sendMessage('unread-count', {});
  }

  sendTypingIndicator(receiverId: number, isTyping: boolean): void {
    this.sendMessage('typing', { receiverId, isTyping });
  }

  get unreadCount$(): Observable<number> {
    return this.unreadCount.asObservable();
  }

  get messages$(): Observable<WebSocketMessage | null> {
    return this.messageSubject.asObservable();
  }

  get notifications$(): Observable<any | null> {
    return this.notificationSubject.asObservable();
  }

  isConnected(): Observable<boolean> {
    return this.connected.asObservable();
  }

  getConnectionStatus(): boolean {
    return this.connected.value;
  }

  refreshConnection(): void {
    this.disconnect();
    setTimeout(() => this.connect(), 500);
  }
}