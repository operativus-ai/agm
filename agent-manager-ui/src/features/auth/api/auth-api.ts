import { ApiClient } from '../../../shared/api/client';
import type {
  LoginRequest,
  RegisterRequest,
  AuthResponse,
  MessageResponse,
  PasswordResetRequestBody,
  PasswordResetConfirmBody,
} from './types';
import { STORAGE_KEYS } from '../../../shared/constants/storage-keys';

export class AuthApi {
  static async login(request: LoginRequest): Promise<AuthResponse> {
    // The endpoint is /auth/login based on AuthController
    // But BASE_URL in client is /api, so we pass /auth/login
    return ApiClient.post<AuthResponse>('/auth/login', request);
  }

  static async register(request: RegisterRequest): Promise<MessageResponse> {
    return ApiClient.post<MessageResponse>('/auth/register', request);
  }

  /**
   * Self-serve reset — request phase. Always resolves on a well-formed email
   * (the BE returns 200 whether or not the address is known — anti-enumeration).
   */
  static async requestPasswordReset(request: PasswordResetRequestBody): Promise<void> {
    return ApiClient.post<void>('/auth/password-reset/request', request);
  }

  /** Self-serve reset — confirm phase. Throws on invalid/expired/used token or weak password (400). */
  static async confirmPasswordReset(request: PasswordResetConfirmBody): Promise<void> {
    return ApiClient.post<void>('/auth/password-reset/confirm', request);
  }

  static logout(): void {
    // Best-effort server-side logout: writes the LOGOUT audit-trail row (system_audits) while the
    // JWT is still present. Fire-and-forget with a catch — a failed/offline call must never block
    // local sign-out. ApiClient reads the Bearer token synchronously before the fetch, so clearing
    // localStorage immediately after is safe (the in-flight request already carries the header).
    ApiClient.post<void>('/auth/logout').catch(() => { /* ignore — proceed with local sign-out */ });
    localStorage.removeItem(STORAGE_KEYS.AUTH_TOKEN);
    localStorage.removeItem(STORAGE_KEYS.AUTH_USER);
  }
}
