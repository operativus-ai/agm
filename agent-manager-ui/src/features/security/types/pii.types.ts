/**
 * PII Governance Domain Types
 * Mapped to Spring Boot Backend Entities (PiiPolicyDTO, PiiAuditLogEntity, etc.)
 */

export type PatternType = 'REGEX' | 'LUHN';

export type ScrubStrategy = 'FPE' | 'REDACT';

export type ComplianceFramework = 'HIPAA' | 'PCI_DSS' | 'GDPR' | 'CCPA' | 'STANDARD';
export type TaxonomyCategory = 'FINANCIAL' | 'MEDICAL' | 'IDENTIFICATION' | 'BIOMETRIC' | 'LOCATION' | 'UNCATEGORIZED';

export interface PiiPolicy {
  id: string; // UUID mapped from java.util.UUID
  name: string;
  description?: string;
  patternType: PatternType;
  pattern: string;
  scrubStrategy: ScrubStrategy;
  enabled: boolean;
  taxonomicCategory: TaxonomyCategory;
  complianceFramework: ComplianceFramework;
}

export interface PiiPolicyCreateRequest {
  name: string;
  description?: string;
  patternType: PatternType;
  pattern: string;
  scrubStrategy: ScrubStrategy;
  enabled: boolean; // Must provide an initial enabled state
  taxonomicCategory?: TaxonomyCategory; // Optional, defaults configured by backend if missing
  complianceFramework?: ComplianceFramework; // Optional
}

/**
 * Maps to com.operativus.agentmanager.compute.security.PiiAuditLogEntity
 */
export interface PiiAuditLogEntry {
  id: string; // UUID
  agentId?: string;
  policyName: string;
  scrubStrategy: string;
  occurrences: number;
  sessionId?: string;
  createdAt: string; // ISO 8601 Date String
}
