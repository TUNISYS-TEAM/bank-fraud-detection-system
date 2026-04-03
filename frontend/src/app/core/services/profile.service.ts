import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface UserProfile {
  id: number;
  uuid: string;
  email: string;
  firstName: string;
  lastName: string;
  phoneNumber: string;
  address: string;
  dateOfBirth: string;
  roleId: number;
  roleName: string;
  managerId: number | null;
  managerName: string | null;
  isActive: boolean;
  lastLogin: string;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateProfileRequest {
  firstName: string;
  lastName: string;
  phoneNumber: string;
  address: string;
  dateOfBirth: string | null;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

@Injectable({ providedIn: 'root' })
export class ProfileService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/profile`;

  getProfile(): Observable<UserProfile> {
    return this.http.get<UserProfile>(this.apiUrl, { withCredentials: true });
  }

  updateProfile(data: UpdateProfileRequest): Observable<UserProfile> {
    return this.http.put<UserProfile>(this.apiUrl, data, { withCredentials: true });
  }

  checkPhoneTaken(phone: string): Observable<boolean> {
    return this.http.get<boolean>(`${this.apiUrl}/check-phone`, { params: { phone }, withCredentials: true });
  }

  changePassword(data: ChangePasswordRequest): Observable<{ message: string }> {
    return this.http.put<{ message: string }>(`${this.apiUrl}/password`, data, { withCredentials: true });
  }
}