package com.operativus.agentmanager.control.controller.observability;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Domain Responsibility: Coerce {@link Object} cells from Hibernate native-query rows
 *     ({@code List<Object[]>}) into typed primitives for the observability aggregate
 *     controllers in this package. The native-query path returns boxed values whose
 *     concrete types depend on the SQL expression and Hibernate's mapping; centralizing
 *     the coercion here keeps every aggregate controller honest about the same edge cases.
 * State: Stateless utility — all methods are static.
 *
 * <p>The {@link #asInstant(Object)} branch order matters: {@code timestamp without time zone}
 * (which Postgres returns from {@code date_trunc(:g, ts AT TIME ZONE 'UTC')}) maps to
 * {@link LocalDateTime}, and its {@code toString()} produces {@code "2026-05-09T00:00"} —
 * an offset-less, second-less form that {@link Instant#parse} rejects with a
 * {@link DateTimeParseException} at index 16. Issues #661/#662/#663 all stemmed from a
 * missing {@link LocalDateTime} branch in per-controller copies of this helper.
 */
final class AggregateRowConverters {

    private AggregateRowConverters() {}

    static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    static long asLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    static double asDouble(Object o) {
        return o == null ? 0.0 : ((Number) o).doubleValue();
    }

    /**
     * Coerce the various time-shapes Hibernate returns from a native query into a UTC
     * {@link Instant}. Order: most-specific JDK types first, then the
     * {@link LocalDateTime}/{@link OffsetDateTime} cases that {@code date_trunc} produces
     * under different Postgres expressions, then a string fallback that prefers the
     * offset-less {@link LocalDateTime} grammar over ISO-8601 with offset.
     */
    static Instant asInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp ts) return ts.toInstant();
        if (o instanceof java.util.Date d) return d.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        if (o instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
        String s = o.toString();
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return Instant.parse(s);
        }
    }
}
