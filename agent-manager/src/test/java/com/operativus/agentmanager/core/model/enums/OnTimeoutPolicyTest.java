package com.operativus.agentmanager.core.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link OnTimeoutPolicy#fromString} — null and unknown values must fall
 * back to {@code AUTO_REJECT} (the safe-by-default per §5 D4 of the unification
 * plan), case-insensitive parse otherwise.
 */
class OnTimeoutPolicyTest {

    @Test
    void fromString_null_returnsAutoReject() {
        assertEquals(OnTimeoutPolicy.AUTO_REJECT, OnTimeoutPolicy.fromString(null));
    }

    @Test
    void fromString_autoRejectExact_returnsAutoReject() {
        assertEquals(OnTimeoutPolicy.AUTO_REJECT, OnTimeoutPolicy.fromString("AUTO_REJECT"));
    }

    @Test
    void fromString_lowercase_isAccepted() {
        assertEquals(OnTimeoutPolicy.AUTO_APPROVE, OnTimeoutPolicy.fromString("auto_approve"));
        assertEquals(OnTimeoutPolicy.CANCEL, OnTimeoutPolicy.fromString("cancel"));
    }

    @Test
    void fromString_mixedCase_isAccepted() {
        assertEquals(OnTimeoutPolicy.AUTO_APPROVE, OnTimeoutPolicy.fromString("Auto_Approve"));
    }

    @Test
    void fromString_unknown_returnsAutoReject() {
        assertEquals(OnTimeoutPolicy.AUTO_REJECT, OnTimeoutPolicy.fromString("garbage"));
        assertEquals(OnTimeoutPolicy.AUTO_REJECT, OnTimeoutPolicy.fromString(""));
    }
}
