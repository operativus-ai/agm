import { ApiClient } from '../../../shared/api/client';

export interface ThreatEvent {
    id: number | string;
    timestamp: string | Date;
    agentId: string;
    threatLevel: string;
    type: string;
    target: string;
    status: string;
}

export interface SandboxCapability {
    agentId: string;
    threadId: string;
    activeCapabilities: string[];
    restrictedPaths: string[];
    memoryIsolation: string;
}

export const securityApi = {
    // These endpoints map to MonitoringController conceptually, await Spring Boot mappings
    getThreatEvents: () => ApiClient.get<ThreatEvent[]>('/monitoring/security/events'),
    
    getSandboxCapabilities: () => ApiClient.get<SandboxCapability[]>('/monitoring/security/sandbox')
};
