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

  constructor() {
    this.ensureGlobalDefined();
  }

  /**
   * Ensures 'global' is defined in browser environment for SockJS
   */
  private ensureGlobalDefined(): void {
    if (typeof window !== 'undefined' && typeof (window as any).global === 'undefined') {
      (window as any).global = window;
    }
  }

  /**
   * Initialize the STOMP client
   */
  private initializeClient(): Client {
    const token = this.authService.getToken() || '';
    
    this.ensureGlobalDefined();

    return new Client({
      webSocketFactory: () => {
        this.ensureGlobalDefined();
        return new SockJS(`${environment.apiUrl}/ws`);
      },
      connectHeaders: {
        'Authorization': `Bearer ${token}`
      },
      debug: (str) => {
        if (!environment.production) {
          console.log('STOMP:', str);
        }
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      logRawCommunication: !environment.production
    });
  }

  /**
   * Connect to WebSocket
   */
  connect(): void {
    this.ensureGlobalDefined();

    const token = this.authService.getToken();
    if (!token) {
      console.warn('Cannot connect WebSocket: No token available');
      return;
    }

    if (this.client?.active) {
      return;
    }

    try {
      if (!this.client) {
        this.client = this.initializeClient();
        this.setupClientEventHandlers();
      }

      if (this.client.connectHeaders) {
        this.client.connectHeaders['Authorization'] = `Bearer ${token}`;
      }

      this.client.activate();
    } catch (error) {
      console.error('Error connecting WebSocket:', error);
      if (error instanceof ReferenceError && error.message.includes('global')) {
        console.log('Retrying with global defined...');
        this.ensureGlobalDefined();
        setTimeout(() => this.connect(), 100);
      }
    }
  }

  /**
   * Set up event handlers for the STOMP client
   */
  private setupClientEventHandlers(): void {
    if (!this.client) return;

    this.client.onConnect = (frame) => {
      console.log('WebSocket connected successfully');
      this.connected.next(true);
      this.reconnectAttempts = 0;
      this.subscribeToUserQueue();
    };

    this.client.onDisconnect = () => {
      console.log('WebSocket disconnected');
      this.connected.next(false);
    };

    this.client.onStompError = (frame) => {
      console.error('STOMP error', frame);
      this.connected.next(false);
    };

    this.client.onWebSocketError = (event) => {
      console.error('WebSocket error', event);
      this.connected.next(false);
      this.handleReconnection();
    };

    this.client.onWebSocketClose = (event) => {
      console.log('WebSocket connection closed', event);
      this.connected.next(false);
    };
  }

  /**
   * Handle reconnection logic
   */
  private handleReconnection(): void {
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++;
      console.log(`Reconnection attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts}`);
      
      const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
      
      setTimeout(() => {
        if (!this.connected.value) {
          this.connect();
        }
      }, delay);
    } else {
      console.error('Max reconnection attempts reached');
    }
  }

  /**
   * Disconnect from WebSocket
   */
  disconnect(): void {
    if (this.client && this.client.active) {
      try {
        this.client.deactivate();
      } catch (error) {
        console.error('Error deactivating WebSocket connection:', error);
      }
    }
  }

  /**
   * Get user ID from token with proper type handling
   */
  private getUserIdFromToken(): number | null {
    const userInfo = this.authService.getUserInfoFromToken();
    
    if (!userInfo) return null;

    // Try different possible claim names where user ID might be stored
    const possibleIdFields = ['sub', 'userId', 'id', 'user_id', 'uid'];
    
    for (const field of possibleIdFields) {
      const value = (userInfo as any)[field];
      if (value !== undefined && value !== null) {
        // Convert to number if it's a string number
        const numValue = Number(value);
        if (!isNaN(numValue)) {
          return numValue;
        }
        return value;
      }
    }
    
    return null;
  }

  /**
   * Subscribe to user-specific queues
   */
  private subscribeToUserQueue(): void {
    if (!this.client || !this.client.connected) return;

    const userId = this.getUserIdFromToken();
    
    if (!userId) {
      console.error('No user ID available for WebSocket subscriptions');
      return;
    }

    try {
      // Subscribe to new messages
      this.client.subscribe(`/user/${userId}/queue/messages`, (message: StompMessage) => {
        try {
          // The message body could be a UUID string or a JSON object
          const body = message.body;
          let payload: any;
          
          try {
            payload = JSON.parse(body);
          } catch {
            payload = body; // Assume it's a message UUID string
          }
          
          this.messageSubject.next({ 
            type: 'NEW_MESSAGE', 
            payload: payload,
            timestamp: new Date().toISOString()
          });
        } catch (error) {
          console.error('Error processing message:', error);
        }
      });

      // Subscribe to unread count updates
      this.client.subscribe(`/user/${userId}/queue/unread`, (message: StompMessage) => {
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

      // Subscribe to message read receipts
      this.client.subscribe(`/user/${userId}/queue/read`, (message: StompMessage) => {
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

      console.log(`Successfully subscribed to user queues for user ${userId}`);
    } catch (error) {
      console.error('Error subscribing to user queues:', error);
    }
  }

  /**
   * Send a message through WebSocket
   * @param destination The destination queue/topic
   * @param payload The message payload
   */
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

  /**
   * Mark a message as read via WebSocket
   * @param messageUuid The UUID of the message to mark as read
   */
  markMessageAsRead(messageUuid: string): void {
    this.sendMessage('mark-read', { messageUuid });
  }

  /**
   * Request current unread count via WebSocket
   */
  requestUnreadCount(): void {
    this.sendMessage('unread-count', {});
  }

  /**
   * Send a typing indicator
   * @param receiverId The ID of the user you're typing to
   * @param isTyping Whether you're typing or stopped typing
   */
  sendTypingIndicator(receiverId: number, isTyping: boolean): void {
    this.sendMessage('typing', { receiverId, isTyping });
  }

  // Observable getters
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

  /**
   * Refresh the connection (useful after token refresh)
   */
  refreshConnection(): void {
    this.disconnect();
    setTimeout(() => {
      this.connect();
    }, 500);
  }
}