package com.operativus.agentmanager.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Regression guard that demo seed changesets (context:"demo" /
 *   "demo_wipe") do NOT run on a normal application boot. Liquibase 5's {@code contexts} is a
 *   positive match-list, not a boolean filter, and {@code SpringLiquibase} exposes no
 *   {@code setContextFilter} — so the prior {@code spring.liquibase.contexts=!demo and !demo-wipe}
 *   negation was silently ignored and demo data (agents/jobs/users) loaded on every boot,
 *   including the prod profile (surfaced by the 2026-06-17 prod-stack boot dry-run). The fix sets
 *   a positive runtime context token that no main (no-context) changeset carries, so demo
 *   changesets are excluded by default; they are seeded explicitly via
 *   {@code ./mvnw liquibase:update -Dliquibase.contexts=demo}.
 *
 *   <p>Asserts against {@code databasechangelog} (NOT data tables) because
 *   {@link BaseIntegrationTest#truncateDatabase()} wipes data tables between tests but leaves the
 *   Liquibase tracking table intact — so it is the authoritative record of which changesets ran.
 * State: Stateless.
 */
class LiquibaseDemoContextExclusionRuntimeTest extends BaseIntegrationTest {

    @Test
    void demoChangesetsAreExcludedOnNormalBoot() {
        Integer demoChangesets = jdbc.queryForObject(
                "SELECT count(*) FROM databasechangelog WHERE id LIKE 'demo-%'", Integer.class);
        assertEquals(0, demoChangesets,
                "demo-* changesets must NOT run on a normal boot — spring.liquibase.contexts must "
                        + "exclude the 'demo' context. Found " + demoChangesets + " demo changesets in "
                        + "databasechangelog (the regression where the custom SpringLiquibase bean "
                        + "ignored spring.liquibase.contexts).");

        // Asserts EXCLUSION, not an empty DB: confirm main (no-context) changesets still applied so
        // a misconfig that skips everything can't pass vacuously. 110 is a stable late main changeset.
        Integer mainApplied = jdbc.queryForObject(
                "SELECT count(*) FROM databasechangelog WHERE id = '110-default-embedding-model'",
                Integer.class);
        assertEquals(1, mainApplied, "main (no-context) changesets must still apply; 110 expected present");

        Integer totalApplied = jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class);
        assertTrue(totalApplied != null && totalApplied > 150,
                "expected the full main changelog to be applied (no-context changesets always run); got " + totalApplied);
    }
}
