import { ApiClient } from '../../../shared/api/client';

export interface AlertIntegration {
    id: string;
    name: string;
    type: 'WEBHOOK' | 'SLACK' | 'PAGERDUTY';
    endpointUrl: string;
    enabled: boolean;
    createdAt?: string;
    /** Retry counter for the single in-flight failed delivery (latest-wins). */
    retryCount?: number;
    /** ISO timestamp of the last failed delivery attempt. */
    lastFailureAt?: string | null;
    /** Error message from the last failed delivery attempt. */
    lastError?: string | null;
    /** ISO timestamp at which the AlertIntegrationService sweep will redispatch. */
    nextRetryAt?: string | null;
    /** Serialized JSON payload of the in-flight failed delivery. Internal queue state. */
    pendingPayload?: string | null;
    /** Source AlertFiredEvent id for the in-flight failed delivery. */
    pendingEventId?: string | null;
    /** Read-only flag from BE: true when a signingSecret is configured.
     *  The raw secret itself is @WRITE_ONLY and never serialized. */
    signingSecretSet?: boolean;
}

export interface AlertIntegrationRequest {
    name: string;
    type: 'WEBHOOK' | 'SLACK' | 'PAGERDUTY';
    endpointUrl: string;
    enabled: boolean;
}

const BASE = '/alerts/integrations';

export class AlertIntegrationApi {
    static async list(): Promise<AlertIntegration[]> {
        return ApiClient.get<AlertIntegration[]>(BASE);
    }

    static async create(req: AlertIntegrationRequest): Promise<AlertIntegration> {
        return ApiClient.post<AlertIntegration>(BASE, req);
    }

    static async update(id: string, req: AlertIntegrationRequest): Promise<AlertIntegration> {
        return ApiClient.put<AlertIntegration>(`${BASE}/${id}`, req);
    }

    static async remove(id: string): Promise<void> {
        return ApiClient.delete<void>(`${BASE}/${id}`);
    }

    /**
     * Operator-fired test dispatch (§4 P5 T040). Backend posts a synthetic
     * AlertFiredEvent payload to the integration's webhook and returns the
     * outcome inline. Always 200 unless the id is unknown (404); a delivery
     * failure is encoded in the response body's `delivered=false` + `message`
     * fields so the UI can render diagnostics without parsing exceptions.
     * Maps to Backend: POST /api/alerts/integrations/{id}/test
     */
    static async testFire(id: string): Promise<AlertIntegrationTestResult> {
        return ApiClient.post<AlertIntegrationTestResult>(`${BASE}/${id}/test`, {});
    }
}

/** Mirrors backend AlertIntegrationTestResult record. */
export interface AlertIntegrationTestResult {
    integrationId: string;
    delivered: boolean;
    statusCode: number;
    message: string;
}
