package com.operativus.agentmanager.control.controller.observability;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asDouble;
import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asInstant;
import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asLong;
import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Focused unit coverage for the asInstant branching that ships in #662 / #663 /
 * the extraction PR. Each branch the live aggregate controllers depend on gets a
 * fixture so any future trim of the helper is caught here, not at runtime.
 */
class AggregateRowConvertersTest {

    private static final Instant TS = Instant.parse("2026-04-25T00:00:00Z");

    @Test
    void asInstant_Null_ReturnsNull() {
        assertNull(asInstant(null));
    }

    @Test
    void asInstant_AlreadyInstant_PassesThrough() {
        assertEquals(TS, asInstant(TS));
    }

    @Test
    void asInstant_FromSqlTimestamp() {
        assertEquals(TS, asInstant(Timestamp.from(TS)));
    }

    @Test
    void asInstant_FromUtilDate() {
        assertEquals(TS, asInstant(java.util.Date.from(TS)));
    }

    @Test
    void asInstant_FromOffsetDateTime() {
        assertEquals(TS, asInstant(TS.atOffset(ZoneOffset.UTC)));
    }

    @Test
    void asInstant_FromLocalDateTime_TreatsAsUtc() {
        // The bug class behind issues #661/#662/#663: Postgres
        // `date_trunc(:g, ts AT TIME ZONE 'UTC')` returns timestamp without time zone,
        // which Hibernate hands back as LocalDateTime.
        assertEquals(TS, asInstant(LocalDateTime.of(2026, 4, 25, 0, 0)));
    }

    @Test
    void asInstant_StringWithoutOffset_ParsesAsLocalDateTimeUtc() {
        // The exact stack-trace input from #661: "2026-05-09T00:00" — Instant.parse
        // would throw at index 16 here. The LocalDateTime-first fallback covers it.
        assertEquals(
                LocalDateTime.of(2026, 5, 9, 0, 0).toInstant(ZoneOffset.UTC),
                asInstant("2026-05-09T00:00"));
    }

    @Test
    void asInstant_StringWithOffset_FallsBackToInstantParse() {
        // Belt-and-suspenders: if some future query path returns the canonical ISO-8601
        // form as a String, the second branch still handles it.
        assertEquals(TS, asInstant("2026-04-25T00:00:00Z"));
    }

    @Test
    void asLong_Null_ReturnsZero() {
        assertEquals(0L, asLong(null));
    }

    @Test
    void asLong_FromInteger_Widens() {
        assertEquals(7L, asLong(7));
    }

    @Test
    void asLong_FromLong_PassesThrough() {
        assertEquals(42L, asLong(42L));
    }

    @Test
    void asDouble_Null_ReturnsZero() {
        assertEquals(0.0, asDouble(null));
    }

    @Test
    void asDouble_FromBigDecimal_Coerces() {
        // Used by SafetyAggregateController + ToolAggregateController for SQL AVG()
        // results, which Hibernate hands back as BigDecimal.
        assertEquals(245.5, asDouble(BigDecimal.valueOf(245.5)));
    }

    @Test
    void asDouble_FromDouble_PassesThrough() {
        assertEquals(3.14, asDouble(3.14));
    }

    @Test
    void asString_Null_ReturnsNull() {
        assertNull(asString(null));
    }

    @Test
    void asString_FromArbitraryObject_UsesToString() {
        assertEquals("ROUTER", asString("ROUTER"));
        assertEquals("123", asString(123));
    }
}
