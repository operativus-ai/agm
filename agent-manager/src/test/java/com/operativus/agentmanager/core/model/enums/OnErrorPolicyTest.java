package com.operativus.agentmanager.core.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link OnErrorPolicy#fromString} — null and unknown values must fall
 * back to {@code CANCEL} (safe-by-default: don't proceed if the approval
 * machinery itself failed).
 */
class OnErrorPolicyTest {

    @Test
    void fromString_null_returnsCancel() {
        assertEquals(OnErrorPolicy.CANCEL, OnErrorPolicy.fromString(null));
    }

    @Test
    void fromString_eachValue_roundTrips() {
        assertEquals(OnErrorPolicy.CANCEL, OnErrorPolicy.fromString("CANCEL"));
        assertEquals(OnErrorPolicy.RETRY, OnErrorPolicy.fromString("RETRY"));
        assertEquals(OnErrorPolicy.CONTINUE, OnErrorPolicy.fromString("CONTINUE"));
    }

    @Test
    void fromString_lowercase_isAccepted() {
        assertEquals(OnErrorPolicy.RETRY, OnErrorPolicy.fromString("retry"));
        assertEquals(OnErrorPolicy.CONTINUE, OnErrorPolicy.fromString("continue"));
    }

    @Test
    void fromString_unknown_returnsCancel() {
        assertEquals(OnErrorPolicy.CANCEL, OnErrorPolicy.fromString("garbage"));
        assertEquals(OnErrorPolicy.CANCEL, OnErrorPolicy.fromString(""));
    }
}
