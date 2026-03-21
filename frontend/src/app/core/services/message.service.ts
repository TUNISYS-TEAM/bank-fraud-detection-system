import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Message, SendMessageRequest, UnreadCount } from '../models/message.model';

@Injectable({
  providedIn: 'root'
})
export class MessageService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/messages`;

  // Envoyer un message
  sendMessage(request: SendMessageRequest): Observable<Message> {
    return this.http.post<Message>(this.baseUrl, request);
  }

  // Boîte de réception
  getInbox(page: number = 0, size: number = 20): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/inbox?page=${page}&size=${size}`);
  }

  // Messages envoyés
  getSent(page: number = 0, size: number = 20): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/sent?page=${page}&size=${size}`);
  }

  // Messages de validation
  getValidationMessages(page: number = 0, size: number = 20): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/validation?page=${page}&size=${size}`);
  }

  // Messages archivés
  getArchived(page: number = 0, size: number = 20): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/archived?page=${page}&size=${size}`);
  }

  // Conversation avec un utilisateur
  getConversation(userId: number, page: number = 0, size: number = 20): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/conversation/${userId}?page=${page}&size=${size}`);
  }

  // Nombre de messages non lus
  getUnreadCount(): Observable<UnreadCount> {
    return this.http.get<UnreadCount>(`${this.baseUrl}/unread/count`);
  }

  // Marquer un message comme lu
  markAsRead(messageUuid: string): Observable<void> {
    return this.http.patch<void>(`${this.baseUrl}/${messageUuid}/read`, {});
  }

  // Marquer tous les messages comme lus
  markAllAsRead(): Observable<void> {
    return this.http.patch<void>(`${this.baseUrl}/read-all`, {});
  }

  // Archiver des messages
  archiveMessages(messageUuids: string[]): Observable<void> {
    return this.http.patch<void>(`${this.baseUrl}/archive`, { messageUuids });
  }

  // Restaurer des messages
  restoreMessages(messageUuids: string[]): Observable<void> {
    return this.http.patch<void>(`${this.baseUrl}/restore`, { messageUuids });
  }

  // Valider ou Rejeter un modèle
  validateModel(payload: { modelUuid: string, status: string, comment?: string }): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/validate-model`, payload);
  }

  // Récupérer un message spécifique
  getMessage(messageUuid: string): Observable<Message> {
    return this.http.get<Message>(`${this.baseUrl}/${messageUuid}`);
  }
}