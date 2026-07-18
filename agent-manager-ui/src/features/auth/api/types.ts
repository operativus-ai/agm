export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  roles?: string[];
}

export interface AuthResponse {
  token: string;
  type: string;
  id: string;
  username: string;
  email: string;
  roles: string[];
}

export interface MessageResponse {
  message: string;
}

export interface PasswordResetRequestBody {
  email: string;
}

export interface PasswordResetConfirmBody {
  token: string;
  newPassword: string;
}

export interface User {
  id: string;
  username: string;
  email: string;
  roles: string[];
  orgId?: string;
}
