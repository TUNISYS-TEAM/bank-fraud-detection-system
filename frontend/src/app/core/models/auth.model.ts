export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  access_token: string;
  refresh_token: string;
  expires_in: number;
  refresh_expires_in: number;
  token_type: string;
  session_state: string;
  scope: string;
}

export interface RefreshTokenRequest {
  refresh_token: string;
}

export interface UserInfo {
  sub: string;
  email: string;
  email_verified: boolean;
  name: string;
  preferred_username: string;
  given_name: string;
  family_name: string;
  realm_access?: {
    roles: string[];
  };
}

export interface AuthUser {
  id: string;
  email: string;
  name: string;
  firstName: string;
  lastName: string;
  role: UserRole;
  permissions: Permission[];
  userId?: number;
  roleId?: number;
  managerId?: number;
}

export type UserRole = 'ADMIN' | 'MANAGER' | 'ANALYST';

export interface Permission {
  id: number;
  name: string;
  description: string;
  action: string;
}

export interface RoleResponse {
  id: number;
  name: string;
  description: string;
  keycloakRoleName: string;
  permissions: Permission[];
}

export interface MenuItem {
  id: number;
  label: string;
  route: string;
  icon: string;
  order: number;
  permissionName: string;
}