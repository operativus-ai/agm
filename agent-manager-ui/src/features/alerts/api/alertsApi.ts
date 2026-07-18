import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiClient } from '../../../shared/api/client';

export interface AlertEvent {
    id: string;
    ruleId: string;
    metricValue: number;
    message: string;
    severity: string;
    acknowledged: boolean;
    firedAt: string;
}

export interface AlertEventPage {
    content: AlertEvent[];
    totalElements: number;
    totalPages: number;
    number: number;
    size: number;
}

const BASE = '/alerts/events';

export class AlertsApi {
    static async getActiveEvents(): Promise<AlertEvent[]> {
        const page = await ApiClient.get<AlertEventPage>(`${BASE}?size=50&sort=firedAt,desc`);
        return page.content.filter(e => !e.acknowledged);
    }

    /**
     * Paginated full-history listing (acknowledged + active). Used by the
     * Observability "Alerts History" tab (T022).
     */
    static async listEvents(params: { page?: number; size?: number } = {}): Promise<AlertEventPage> {
        const sp = new URLSearchParams();
        sp.set('page', String(params.page ?? 0));
        sp.set('size', String(params.size ?? 25));
        sp.set('sort', 'firedAt,desc');
        return ApiClient.get<AlertEventPage>(`${BASE}?${sp.toString()}`);
    }

    static async acknowledge(id: string): Promise<void> {
        return ApiClient.post<void>(`${BASE}/${id}/acknowledge`, {});
    }
}

export const useActiveAlerts = () => {
    return useQuery({
        queryKey: ['alerts', 'active'],
        queryFn: AlertsApi.getActiveEvents,
        refetchInterval: 30_000,
        staleTime: 15_000,
    });
};

export const useAcknowledgeAlert = () => {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: (id: string) => AlertsApi.acknowledge(id),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['alerts', 'active'] });
        },
    });
};
