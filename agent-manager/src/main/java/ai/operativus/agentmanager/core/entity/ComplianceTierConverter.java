package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Domain Responsibility: JPA {@link AttributeConverter} that hydrates the {@code
 *     compliance_tier} column into {@link ComplianceTier}, routing unknown / legacy
 *     string values (e.g. {@code TIER_3_REGULATED} from earlier demo seeds) through
 *     {@link ComplianceTier#fromString(String)}'s safe-fallback path instead of letting
 *     Hibernate's default enum mapper throw {@code IllegalArgumentException}.
 *
 *     <p><b>Why this exists:</b> {@code AgentEntity.complianceTier} previously relied on
 *     Hibernate's implicit varchar-to-enum coercion (no {@code @Enumerated}, no converter).
 *     A single row carrying an unknown enum value would propagate as an unchecked
 *     exception during {@code Page<AgentEntity>} hydration and 500 the entire
 *     {@code GET /api/admin/agents} list — every other healthy row in the page taken down
 *     by one bad seed value. The converter caps the blast radius at the offending row by
 *     coercing it to {@code TIER_1_STANDARD}, matching the existing {@code @JsonCreator}
 *     contract for inbound DTOs so the read and write paths are symmetric.
 *
 * State: Stateless.
 */
@Converter(autoApply = false)
public class ComplianceTierConverter implements AttributeConverter<ComplianceTier, String> {

    @Override
    public String convertToDatabaseColumn(ComplianceTier tier) {
        return tier == null ? null : tier.name();
    }

    @Override
    public ComplianceTier convertToEntityAttribute(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        return ComplianceTier.fromString(dbValue);
    }
}
