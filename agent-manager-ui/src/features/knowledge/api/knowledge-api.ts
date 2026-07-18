import type { KnowledgeDocument, KnowledgeUploadResponse, PaginatedResponse } from '../../../shared/types/api';
import { ApiClient } from '../../../shared/api/client';
import { STORAGE_KEYS } from '../../../shared/constants/storage-keys';

const API_BASE_URL = '/knowledge';

export interface DocumentPreview {
  id: string;
  name: string;
  description: string | null;
  contentType: string;
  uri: string | null;
  size: number | null;
  status: string;
  statusMessage: string | null;
  metadata: Record<string, unknown> | null;
  chunkCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChunkDetail {
  text: string;
  metadata: Record<string, unknown>;
}

export interface DocumentChunkDetails {
  documentId: string;
  documentName: string;
  totalChunks: number;
  chunks: ChunkDetail[];
}

export class KnowledgeApi {

  /**
   * List ingested documents with server-side pagination.
   * Maps to Backend: GET /api/knowledge?page=N&size=M
   */
  static async getDocuments(params?: { page?: number; size?: number; knowledgeBaseId?: string }): Promise<PaginatedResponse<KnowledgeDocument>> {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    if (params?.knowledgeBaseId) sp.append('knowledgeBaseId', params.knowledgeBaseId);
    return ApiClient.get<PaginatedResponse<KnowledgeDocument>>(`${API_BASE_URL}?${sp.toString()}`);
  }

  static async getDocumentById(id: string): Promise<KnowledgeDocument> {
    return ApiClient.get<KnowledgeDocument>(`${API_BASE_URL}/${id}`);
  }

  static async ingestUrl(url: string, knowledgeBaseId?: string): Promise<KnowledgeUploadResponse> {
    const sp = new URLSearchParams({ url });
    if (knowledgeBaseId) sp.append('knowledgeBaseId', knowledgeBaseId);
    return ApiClient.post<KnowledgeUploadResponse>(`${API_BASE_URL}/ingest-url?${sp.toString()}`, {});
  }

  /**
   * Delete a document by ID.
   */
  static async deleteDocument(id: string): Promise<void> {
    return ApiClient.delete<void>(`${API_BASE_URL}/${id}`);
  }

  /**
   * Retry a failed URL-sourced ingestion. Backend returns:
   *  - 202 on retry launch (URL re-fetch will run async)
   *  - 422 when document is a file upload (raw bytes are not persisted; user must re-upload)
   *  - 400 when the document isn't in FAILED status
   *  - 404 when the document doesn't exist
   * Caller surfaces 422 as an info toast; this method propagates the error so the UI can decide.
   */
  static async retryIngestion(id: string): Promise<{ status: string }> {
    return ApiClient.post<{ status: string }>(`${API_BASE_URL}/${id}/retry`, {});
  }

  /**
   * Get raw vector database chunks
   */
  static async getDocumentChunks(id: string): Promise<any[]> {
    return ApiClient.get<any[]>(`${API_BASE_URL}/${id}/chunks`);
  }

  /**
   * Document preview: metadata + chunk count.
   * Maps to KnowledgePreviewController GET /api/knowledge/{id}/preview
   */
  static async getDocumentPreview(id: string): Promise<DocumentPreview> {
    return ApiClient.get<DocumentPreview>(`${API_BASE_URL}/${id}/preview`);
  }

  /**
   * Chunk details: full text + metadata for each vector chunk.
   * Maps to KnowledgePreviewController GET /api/knowledge/{id}/chunks/detail
   */
  static async getChunkDetails(id: string): Promise<DocumentChunkDetails> {
    return ApiClient.get<DocumentChunkDetails>(`${API_BASE_URL}/${id}/chunks/detail`);
  }

  /**
   * Semantic Search against PGVector using Spring AI
   */
  static async search(query: string): Promise<any[]> {
    return ApiClient.get<any[]>(`${API_BASE_URL}/search?query=${encodeURIComponent(query)}`);
  }

  /**
   * Upload multiple files in one request. Returns accepted documentIds and rejected file reasons.
   */
  static async uploadBatch(
    files: File[],
    knowledgeBaseId?: string,
    description?: string
  ): Promise<{ accepted: string[]; rejected: string[] }> {
    const formData = new FormData();
    for (const file of files) formData.append('files', file);
    if (knowledgeBaseId) formData.append('knowledgeBaseId', knowledgeBaseId);
    if (description) formData.append('description', description);
    const token = localStorage.getItem(STORAGE_KEYS.AUTH_TOKEN);
    const headers: Record<string, string> = {};
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const response = await fetch(`/api${API_BASE_URL}/upload-batch`, { method: 'POST', headers, body: formData });
    if (response.status === 401) {
      localStorage.removeItem(STORAGE_KEYS.AUTH_TOKEN);
      localStorage.removeItem(STORAGE_KEYS.AUTH_USER);
      window.location.href = '/login';
      throw new Error('Unauthorized');
    }
    if (!response.ok) throw new Error(`Batch upload failed: ${response.statusText}`);
    return response.json();
  }

  /**
   * Move a document to a different knowledge base.
   */
  static async moveDocument(documentId: string, targetKbId: string): Promise<void> {
    return ApiClient.patch<void>(`${API_BASE_URL}/${documentId}/move`, { knowledgeBaseId: targetKbId });
  }

  /**
   * Subscribe to SSE ingestion status for a document. Uses the authenticated
   * fetch-EventSource pipeline so the JWT is attached on the SSE handshake
   * (native EventSource can't carry headers; the endpoint requires auth).
   * Returns an AbortController — caller must abort() it when done.
   */
  static subscribeToIngestionStatus(
    documentId: string,
    onStatus: (status: string, message: string) => void,
    onError?: () => void
  ): AbortController {
    return ApiClient.stream(`${API_BASE_URL}/${documentId}/status/stream`, {
      onMessage: ({ event, data }) => {
        if (event !== 'ingestion-status') return;
        const parsed = JSON.parse(data);
        onStatus(parsed.status, parsed.message ?? '');
      },
      onError: () => {
        onError?.();
        throw new Error('stop-retry');
      },
    });
  }

}
