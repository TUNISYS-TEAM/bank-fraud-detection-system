import { Injectable, inject } from '@angular/core';
import { Client, Message as StompMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import { BehaviorSubject, Observable } from 'rxjs';

export interface WebSocketMessage {
  type: 'NEW_MESSAGE' | 'MESSAGE_READ' | 'UNREAD_COUNT';
  payload: any;
  timestamp: string;
}

@Injectable({
  providedIn: 'root'
})
export class WebSocketService {
  private authService = inject(AuthService);

  private client: Client | null = null;
  private connected = new BehaviorSubject<boolean>(false);
  private unreadCount = new BehaviorSubject<number>(0);
  private messageSubject = new BehaviorSubject<WebSocketMessage | null>(null);
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private connectionInProgress = false;

  constructor() {
    this.ensureGlobalDefined();
  }

  private ensureGlobalDefined(): void {
    if (typeof window !== 'undefined' && typeof (window as any).global === 'undefined') {
      (window as any).global = window;
    }
  }

  private initializeClient(): Client {
    this.ensureGlobalDefined();

    return new Client({
      webSocketFactory: () => {
        this.ensureGlobalDefined();
        // withCredentials ensures HttpOnly cookies are sent during SockJS handshake
        return new SockJS(`${environment.apiUrl}/ws`, null, {
          withCredentials: true
        } as any);
      },
      // No connectHeaders needed — token comes from HttpOnly cookie
      debug: (str) => {
        if (!environment.production) {
          console.log('STOMP:', str);
        }
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      logRawCommunication: !environment.production,
      connectionTimeout: 10000,
      splitLargeFrames: true
    });
  }

  connect(): void {
    if (this.connectionInProgress || this.client?.active) {
      return;
    }

    this.connectionInProgress = true;
    this.ensureGlobalDefined();

    try {
      if (!this.client) {
        this.client = this.initializeClient();
        this.setupClientEventHandlers();
      }
      this.client.activate();
    } catch (error) {
      console.error('Error connecting WebSocket:', error);
      this.connectionInProgress = false;

      if (error instanceof ReferenceError && error.message.includes('global')) {
        this.ensureGlobalDefined();
        setTimeout(() => this.connect(), 100);
      }
    }
  }

  private setupClientEventHandlers(): void {
    if (!this.client) return;

    this.client.onConnect = () => {
      console.log('WebSocket connected successfully');
      this.connected.next(true);
      this.reconnectAttempts = 0;
      this.connectionInProgress = false;
      this.subscribeToUserQueue();
    };

    this.client.onDisconnect = () => {
      console.log('WebSocket disconnected');
      this.connected.next(false);
      this.connectionInProgress = false;
    };

    this.client.onStompError = (frame) => {
      console.error('STOMP error', frame);
      this.connected.next(false);
      this.connectionInProgress = false;
      this.handleReconnection();
    };

    this.client.onWebSocketError = (event) => {
      console.error('WebSocket error', event);
      this.connected.next(false);
      this.connectionInProgress = false;
      this.handleReconnection();
    };

    this.client.onWebSocketClose = (event) => {
      console.log('WebSocket connection closed', event);
      this.connected.next(false);
      this.connectionInProgress = false;
    };
  }

  private handleReconnection(): void {
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++;
      console.log(`Reconnection attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts}`);
      const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
      setTimeout(() => {
        if (!this.connected.value && !this.connectionInProgress) {
          this.connect();
        }
      }, delay);
    } else {
      console.error('Max reconnection attempts reached');
    }
  }

  disconnect(): void {
    if (this.client && this.client.active) {
      try {
        this.client.deactivate();
      } catch (error) {
        console.error('Error deactivating WebSocket connection:', error);
      }
    }
    this.connected.next(false);
    this.connectionInProgress = false;
  }

  private subscribeToUserQueue(): void {
    if (!this.client || !this.client.connected) return;

    try {
      // Spring resolves /user/queue/* to the authenticated principal automatically.
      // Do NOT put user ID in the path — Spring handles routing by principal name.
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
          console.error('Error processing message:', error);
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
          console.error('Error processing unread count:', error);
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
          console.error('Error processing read receipt:', error);
        }
      });

      console.log('Successfully subscribed to user queues');
    } catch (error) {
      console.error('Error subscribing to user queues:', error);
    }
  }

  sendMessage(destination: string, payload: any): void {
    if (!this.client || !this.client.connected) {
      console.error('Cannot send message: WebSocket not connected');
      return;
    }
    try {
      this.client.publish({
        destination: `/app/${destination}`,
        body: JSON.stringify(payload)
      });
    } catch (error) {
      console.error('Error sending message:', error);
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