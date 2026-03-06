export interface Message {
  id: number;
  messageUuid: string;
  senderId: number;
  senderEmail: string;
  senderName: string;
  receiverId: number;
  receiverEmail: string;
  receiverName: string;
  subject: string;
  content: string;
  isRead: boolean;
  readAt?: string;
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
  createdAt: string;
  parentMessageUuid?: string;
}

export interface SendMessageRequest {
  receiverId: number;
  subject?: string;
  content: string;
  priority?: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
  parentMessageUuid?: string;
}

export interface Conversation {
  userId: number;
  userName: string;
  lastMessage: Message;
  unreadCount: number;
}

export interface UnreadCount {
  count: number;
}