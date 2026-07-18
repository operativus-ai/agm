package com.operativus.agentmanager.core.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Domain Responsibility: Pin the {@link ComplianceTierConverter} contract from
 * PR #929 (Bug #14b).
 *
 * Pre-fix, {@code AgentEntity.complianceTier} relied on Hibernate's implicit
 * varchar-to-enum coercion (no {@code @Enumerated}, no converter). A single
 * row carrying an unknown enum value — e.g. {@code TIER_3_REGULATED} from
 * earlier demo seeds — would propagate as an unchecked
 * {@code IllegalArgumentException} during {@code Page<AgentEntity>}
 * hydration and 500 the entire {@code GET /api/admin/agents} list. Every
 * other healthy row in the page would be taken down by one bad seed value.
 *
 * Post-fix the {@code @Convert(converter=ComplianceTierConverter.class)}
 * annotation routes hydration through {@link ComplianceTier#fromString} which
 * coerces unknown / legacy strings to {@code TIER_1_STANDARD} — capping the
 * blast radius at the offending row.
 *
 * State: Stateless.
 */
class ComplianceTierConverterTest {

    private final ComplianceTierConverter converter = new ComplianceTierConverter();

    // ─── convertToDatabaseColumn (write path) ───────────────────────────────

    @Test
    void writeNull_persistsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void writeKnownTiers_persistsEnumName() {
        assertThat(converter.convertToDatabaseColumn(ComplianceTier.TIER_1_STANDARD))
                .isEqualTo("TIER_1_STANDARD");
        assertThat(converter.convertToDatabaseColumn(ComplianceTier.TIER_2_STRICT))
                .isEqualTo("TIER_2_STRICT");
    }

    // ─── convertToEntityAttribute (read path) ───────────────────────────────

    @Test
    void readNull_returnsNull() {
        // null column → null entity field, matching the JPA-converter null
        // contract. Distinct from the blank-string fallback below.
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void readKnownTiers_returnsMatchingEnum() {
        assertThat(converter.convertToEntityAttribute("TIER_1_STANDARD"))
                .isEqualTo(ComplianceTier.TIER_1_STANDARD);
        assertThat(converter.convertToEntityAttribute("TIER_2_STRICT"))
                .isEqualTo(ComplianceTier.TIER_2_STRICT);
    }

    @Test
    void readLegacyTier3Regulated_coercesToTier1NotThrow() {
        // Bug #14b regression guard. TIER_3_REGULATED appeared in earlier
        // demo seeds (demo-003-agents.sql:11) before PR #936 corrected it.
        // The converter must NOT throw — instead coerce to TIER_1_STANDARD
        // so the page hydration continues for the remaining rows.
        assertThatCode(() -> converter.convertToEntityAttribute("TIER_3_REGULATED"))
                .as("legacy TIER_3_REGULATED must not throw — pre-fix this would 500 the entire agents-list page")
                .doesNotThrowAnyException();
        assertThat(converter.convertToEntityAttribute("TIER_3_REGULATED"))
                .isEqualTo(ComplianceTier.TIER_1_STANDARD);
    }

    @Test
    void readArbitraryUnknownValue_coercesToTier1() {
        // Any other unknown value (typo, future enum removed, garbage data)
        // must also fall through safely.
        assertThat(converter.convertToEntityAttribute("totally-unknown-tier"))
                .isEqualTo(ComplianceTier.TIER_1_STANDARD);
    }

    @Test
    void readBlankString_coercesToTier1() {
        // ComplianceTier.fromString's null/blank guard applies here too —
        // distinct from the null-column path which short-circuits in the
        // converter itself.
        assertThat(converter.convertToEntityAttribute("")).isEqualTo(ComplianceTier.TIER_1_STANDARD);
        assertThat(converter.convertToEntityAttribute("   ")).isEqualTo(ComplianceTier.TIER_1_STANDARD);
    }

    @Test
    void readCaseInsensitive_matchesViaEqualsIgnoreCase() {
        // fromString uses equalsIgnoreCase — lowercase / mixed-case persisted
        // values still resolve. Pinned so a future strict-case refactor surfaces.
        assertThat(converter.convertToEntityAttribute("tier_2_strict"))
                .isEqualTo(ComplianceTier.TIER_2_STRICT);
        assertThat(converter.convertToEntityAttribute("Tier_1_Standard"))
                .isEqualTo(ComplianceTier.TIER_1_STANDARD);
    }

    @Test
    void readNumericHierarchyValue_matchesViaHierarchyString() {
        // fromString accepts the hierarchy integer as a string as an
        // alternate identity. Pinned because the @JsonCreator on
        // ComplianceTier exposes this to inbound DTOs as well — read and
        // write paths are intentionally symmetric (per the converter javadoc).
        assertThat(converter.convertToEntityAttribute("1"))
                .isEqualTo(ComplianceTier.TIER_1_STANDARD);
        assertThat(converter.convertToEntityAttribute("2"))
                .isEqualTo(ComplianceTier.TIER_2_STRICT);
    }

    // ─── Round-trip invariants ──────────────────────────────────────────────

    @Test
    void knownTiers_roundTripThroughDbAndBack() {
        for (ComplianceTier tier : ComplianceTier.values()) {
            String persisted = converter.convertToDatabaseColumn(tier);
            ComplianceTier readBack = converter.convertToEntityAttribute(persisted);
            assertThat(readBack)
                    .as("round-trip must preserve identity for known enum %s", tier)
                    .isEqualTo(tier);
        }
    }
}
