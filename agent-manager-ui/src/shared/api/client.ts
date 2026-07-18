import { logger } from '../../utils/logger';
import { fetchEventSource, EventStreamContentType } from '@microsoft/fetch-event-source';
import { STORAGE_KEYS } from '../constants/storage-keys';

const BASE_URL = '/api';

interface RequestOptions extends RequestInit {
  headers?: Record<string, string>;
}

export class ApiError extends Error {
  public status: number;
  public fields?: Record<string, string>;
  /** Parsed response body, if any. Useful for endpoints that return a discriminator
   *  field (e.g. {@code 409 {reason:"not_failed"}}) that callers want to branch on. */
  public body?: unknown;

  constructor(message: string, status: number, fields?: Record<string, string>, body?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fields = fields;
    this.body = body;
  }
}

function generateTraceparent(): string {
  // Format: 00-{traceId(32 hex)}-{spanId(16 hex)}-01
  const traceId = crypto.randomUUID().replace(/-/g, '');
  const spanId = crypto.randomUUID().replace(/-/g, '').substring(0, 16);
  return `00-${traceId}-${spanId}-01`;
}

export class ApiClient {
  private static getToken(): string | null {
    return localStorage.getItem(STORAGE_KEYS.AUTH_TOKEN);
  }

  static async request<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
    const token = this.getToken();
    const traceparent = generateTraceparent();
    
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'traceparent': traceparent,
      ...options.headers,
    };

    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    const method = options.method || 'GET';
    const startTime = performance.now();
    logger.info(`API Request: [${method}] ${endpoint}`, { traceparent });

    const response = await fetch(`${BASE_URL}${endpoint}`, {
      ...options,
      headers,
    });

    const duration = Math.round(performance.now() - startTime);

    if (response.status === 401) {
      logger.warn(`API Unauthorized: [${method}] ${endpoint} (${duration}ms)`, { status: 401, traceparent });
      // Handle unauthorized access (e.g., redirect to login)
      localStorage.removeItem(STORAGE_KEYS.AUTH_TOKEN);
      localStorage.removeItem(STORAGE_KEYS.AUTH_USER);
      
      // Only redirect if we are not already on the login page
      if (!window.location.pathname.includes('/login')) {
        window.location.href = '/login';
      }
      
      throw new Error('Invalid username or password');
    }

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      logger.error(`API Error: [${method}] ${endpoint} (${duration}ms)`, { status: response.status, traceparent, error: errorData });
      
      if (response.status === 409) {
          // Default 409 message assumes optimistic-concurrency conflict — preserve the
          // parsed body so endpoints with a discriminator (e.g. retry's {reason:"not_failed"})
          // can read it via err.body.
          throw new ApiError(
            "Stale Data: This record was modified by another user. Please refresh and try again.",
            409,
            undefined,
            errorData,
          );
      }

      // Support RFC 7807 ProblemDetails (Spring Boot 3 default)
      const errorMessage = errorData.detail 
        || errorData.message 
        || errorData.title 
        || `API Error: ${response.statusText}`;

      // Append field-specific validation errors if present via Spring validation
      const validationErrors = errorData.invalidParams || errorData.properties?.invalidParams || [];
      const validationText = Array.isArray(validationErrors) && validationErrors.length > 0
        ? ` (${validationErrors.map((e: any) => `${e.name || e.field}: ${e.reason || e.message}`).join(', ')})`
        : '';
        
      const fields: Record<string, string> = {};
      if (Array.isArray(validationErrors)) {
          validationErrors.forEach((e: any) => {
              if (e.name || e.field) {
                  fields[e.name || e.field] = e.reason || e.message || 'Invalid value';
              }
          });
      }
        
      throw new ApiError(
        errorMessage + validationText,
        response.status,
        Object.keys(fields).length > 0 ? fields : undefined,
        errorData,
      );
    }

    logger.debug(`API Success: [${method}] ${endpoint} (${duration}ms)`, { status: response.status, traceparent });

    // Handle empty responses — 204 No Content always carries no body, and some
    // endpoints (e.g. POST /api/models/test) return 200 OK with ResponseEntity<Void>.
    // response.json() throws "Unexpected end of JSON input" on an empty body, so
    // read as text first and only parse when there's actual content.
    if (response.status === 204) {
      return {} as T;
    }
    const text = await response.text();
    if (!text) {
      return {} as T;
    }
    try {
      return JSON.parse(text) as T;
    } catch {
      // Endpoint returned a non-empty non-JSON body (rare — most non-204 success
      // paths are typed JSON). Treat as empty to avoid crashing the caller.
      logger.warn(`API non-JSON body: [${method}] ${endpoint}`, { status: response.status, bodyPreview: text.slice(0, 100) });
      return {} as T;
    }
  }

  static async get<T>(endpoint: string): Promise<T> {
    return this.request<T>(endpoint, { method: 'GET' });
  }

  static async post<T>(endpoint: string, body?: any): Promise<T> {
    const options: RequestOptions = { method: 'POST' };
    if (body) options.body = JSON.stringify(body);
    return this.request<T>(endpoint, options);
  }

  static async put<T>(endpoint: string, body?: any): Promise<T> {
    const options: RequestOptions = { method: 'PUT' };
    if (body) options.body = JSON.stringify(body);
    return this.request<T>(endpoint, options);
  }

  static async patch<T>(endpoint: string, body?: any): Promise<T> {
    const options: RequestOptions = { method: 'PATCH' };
    if (body) options.body = JSON.stringify(body);
    return this.request<T>(endpoint, options);
  }

  static async delete<T>(endpoint: string, body?: any): Promise<T> {
    const options: RequestOptions = { method: 'DELETE' };
    if (body) options.body = JSON.stringify(body);
    return this.request<T>(endpoint, options);
  }

  /**
   * Establishes an SSE (Server-Sent Events) stream using the Fetch API,
   * enabling secure Authorization header injection and OpenTelemetry trace propagation.
   *
   * @param endpoint - The API path relative to BASE_URL (e.g., '/mcp/sse')
   * @param options - Configuration for event handling and request customization
   * @returns AbortController to allow the caller to close the stream
   */
  static stream(
    endpoint: string,
    options: {
      /** Called for each SSE message event. */
      onMessage: (event: { event: string; data: string }) => void;
      /** Called when the connection is first opened. */
      onOpen?: () => void;
      /** Called on stream errors. Return nothing to retry, throw to stop. */
      onError?: (err: any) => void;
      /** Called when the stream is closed. */
      onClose?: () => void;
      /** HTTP method — defaults to 'GET'. Use 'POST' for A2A payload streams. */
      method?: string;
      /** Optional JSON body for POST-based streams. */
      body?: any;
      /** If true, the endpoint is treated as an absolute path (not prefixed with /api). */
      absolutePath?: boolean;
    }
  ): AbortController {
    const token = this.getToken();
    const traceparent = generateTraceparent();
    const ctrl = new AbortController();

    const headers: Record<string, string> = {
      'Accept': EventStreamContentType,
      'traceparent': traceparent,
    };

    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    if (options.body) {
      headers['Content-Type'] = 'application/json';
    }

    logger.info(`SSE Stream: [${options.method || 'GET'}] ${endpoint}`, { traceparent });

    const url = options.absolutePath ? endpoint : `${BASE_URL}${endpoint}`;

    fetchEventSource(url, {
      method: options.method || 'GET',
      headers,
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: ctrl.signal,
      openWhenHidden: true, // Keep stream alive when tab is backgrounded

      async onopen(response) {
        if (response.ok && response.headers.get('content-type')?.includes(EventStreamContentType)) {
          logger.debug(`SSE Connected: ${endpoint}`, { traceparent });
          options.onOpen?.();
          return;
        }

        if (response.status === 401) {
          logger.warn(`SSE Unauthorized: ${endpoint}`, { traceparent });
          localStorage.removeItem(STORAGE_KEYS.AUTH_TOKEN);
          localStorage.removeItem(STORAGE_KEYS.AUTH_USER);
          if (!window.location.pathname.includes('/login')) {
            window.location.href = '/login';
          }
          throw new Error('Unauthorized');
        }

        throw new Error(`SSE open failed: ${response.status} ${response.statusText}`);
      },

      onmessage(msg) {
        options.onMessage({ event: msg.event, data: msg.data });
      },

      onerror(err) {
        logger.error(`SSE Error: ${endpoint}`, { error: err, traceparent });
        if (options.onError) {
          options.onError(err);
        }
        // Returning nothing causes fetchEventSource to retry.
        // Throw to stop retrying.
        throw err;
      },

      onclose() {
        logger.debug(`SSE Closed: ${endpoint}`, { traceparent });
        options.onClose?.();
      },
    });

    return ctrl;
  }
}
