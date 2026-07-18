package com.operativus.agentmanager.core.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link RunStatus#fromValue} parsing for every enum value plus the null + unknown
 * paths. The REQ-DR-4 addition ({@link RunStatus#AWAITING_ROUTE_SELECTION}) is explicitly
 * covered so the upcoming router-selector pipeline can be confident the state round-trips
 * through JSON serialization without falling back to the generic "Unknown status type"
 * exception.
 */
public class RunStatusTest {

    @Test
    void fromValue_awaitingRouteSelection_returnsEnum() {
        // REQ-DR-4 addition — distinct from PAUSED (HITL approval) so resume dispatch
        // can branch on the exact suspension reason.
        assertEquals(RunStatus.AWAITING_ROUTE_SELECTION, RunStatus.fromValue("AWAITING_ROUTE_SELECTION"));
    }

    @Test
    void fromValue_awaitingRouteSelectionMixedCase_returnsEnum() {
        assertEquals(RunStatus.AWAITING_ROUTE_SELECTION, RunStatus.fromValue("awaiting_route_selection"));
    }

    @Test
    void fromValue_pending_returnsPending() {
        assertEquals(RunStatus.PENDING, RunStatus.fromValue("PENDING"));
    }

    @Test
    void fromValue_paused_returnsPaused() {
        assertEquals(RunStatus.PAUSED, RunStatus.fromValue("PAUSED"));
    }

    @Test
    void fromValue_running_returnsRunning() {
        assertEquals(RunStatus.RUNNING, RunStatus.fromValue("running"));
    }

    @Test
    void fromValue_completed_returnsCompleted() {
        assertEquals(RunStatus.COMPLETED, RunStatus.fromValue("COMPLETED"));
    }

    @Test
    void fromValue_cancelled_returnsCancelled() {
        assertEquals(RunStatus.CANCELLED, RunStatus.fromValue("CANCELLED"));
    }

    @Test
    void fromValue_failed_returnsFailed() {
        assertEquals(RunStatus.FAILED, RunStatus.fromValue("FAILED"));
    }

    @Test
    void fromValue_null_returnsNull() {
        // Contract preserved: fromValue(null) returns null — used by deserializers
        // that need to round-trip a JSON null without throwing.
        assertNull(RunStatus.fromValue(null));
    }

    @Test
    void fromValue_unknownValue_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RunStatus.fromValue("NONEXISTENT_STATE"));
        assertEquals("Unknown status type: NONEXISTENT_STATE", ex.getMessage());
    }

    @Test
    void awaitingRouteSelectionJsonValueRoundTrip() {
        // @JsonValue contract — getValue() returns the same string fromValue accepts.
        assertEquals("AWAITING_ROUTE_SELECTION", RunStatus.AWAITING_ROUTE_SELECTION.getValue());
    }
}
