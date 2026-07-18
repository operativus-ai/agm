package com.operativus.agentmanager.compute.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EntityListeners;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain Responsibility: Represents a single PII detection rule in a tenant's policy dictionary.
 * Each rule defines a regex/Luhn pattern and a scrubbing strategy (FPE or REDACT).
 * Administrators bind these policies to specific agents via the {@code agent_pii_policies} join table.
 * Per-tenant: every policy belongs to exactly one {@code org_id}; admins only see policies
 * within their own tenant. Distinct tenants may use the same policy {@code name} (enforced by
 * the composite {@code (org_id, name)} unique constraint, changeset 101).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "pii_policies")
@EntityListeners(AuditingEntityListener.class)
public class PiiPolicyEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, length = 255)
    private String orgId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "pattern_type", nullable = false, length = 50)
    private PatternType patternType;

    @Column(name = "pattern", nullable = false, length = 1024)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "scrub_strategy", nullable = false, length = 50)
    private ScrubStrategy scrubStrategy;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "taxonomic_category", length = 50)
    private TaxonomyCategory taxonomicCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_framework", length = 50)
    private ComplianceFramework complianceFramework;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public PiiPolicyEntity() {
    }

    public PiiPolicyEntity(UUID id, String orgId, String name, String description, PatternType patternType,
                           String pattern, ScrubStrategy scrubStrategy, Boolean enabled,
                           TaxonomyCategory taxonomicCategory, ComplianceFramework complianceFramework) {
        this.id = id;
        this.orgId = orgId;
        this.name = name;
        this.description = description;
        this.patternType = patternType;
        this.pattern = pattern;
        this.scrubStrategy = scrubStrategy;
        this.enabled = enabled != null ? enabled : true;
        this.taxonomicCategory = taxonomicCategory != null ? taxonomicCategory : TaxonomyCategory.UNCATEGORIZED;
        this.complianceFramework = complianceFramework != null ? complianceFramework : ComplianceFramework.STANDARD;
    }

    // --- Getters and Setters ---

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PatternType getPatternType() {
        return patternType;
    }

    public void setPatternType(PatternType patternType) {
        this.patternType = patternType;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public ScrubStrategy getScrubStrategy() {
        return scrubStrategy;
    }

    public void setScrubStrategy(ScrubStrategy scrubStrategy) {
        this.scrubStrategy = scrubStrategy;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public TaxonomyCategory getTaxonomicCategory() {
        return taxonomicCategory;
    }

    public void setTaxonomicCategory(TaxonomyCategory taxonomicCategory) {
        this.taxonomicCategory = taxonomicCategory;
    }

    public ComplianceFramework getComplianceFramework() {
        return complianceFramework;
    }

    public void setComplianceFramework(ComplianceFramework complianceFramework) {
        this.complianceFramework = complianceFramework;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PiiPolicyEntity that = (PiiPolicyEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
