package com.operativus.agentmanager.core.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link OnRejectPolicy#fromString} parsing. The CONDITION step dispatcher
 * uses {@code null → SKIP} fallback so unconfigured rows preserve pre-DR-6
 * behavior; unknown values also fall back to SKIP defensively (a malformed
 * column value can't crash a workflow run).
 */
class OnRejectPolicyTest {

    @Test
    void fromString_null_returnsSkip() {
        assertEquals(OnRejectPolicy.SKIP, OnRejectPolicy.fromString(null));
    }

    @Test
    void fromString_skipExact_returnsSkip() {
        assertEquals(OnRejectPolicy.SKIP, OnRejectPolicy.fromString("SKIP"));
    }

    @Test
    void fromString_skipLowercase_returnsSkip() {
        assertEquals(OnRejectPolicy.SKIP, OnRejectPolicy.fromString("skip"));
    }

    @Test
    void fromString_cancel_returnsCancel() {
        assertEquals(OnRejectPolicy.CANCEL, OnRejectPolicy.fromString("CANCEL"));
        assertEquals(OnRejectPolicy.CANCEL, OnRejectPolicy.fromString("Cancel"));
    }

    @Test
    void fromString_elseBranch_returnsElseBranch() {
        assertEquals(OnRejectPolicy.ELSE_BRANCH, OnRejectPolicy.fromString("ELSE_BRANCH"));
        assertEquals(OnRejectPolicy.ELSE_BRANCH, OnRejectPolicy.fromString("else_branch"));
        assertEquals(OnRejectPolicy.ELSE_BRANCH, OnRejectPolicy.fromString("Else_Branch"));
    }

    @Test
    void fromString_unknown_returnsSkip() {
        // A misconfigured row carrying an unknown policy string must fall back
        // to SKIP rather than crash the dispatcher mid-run.
        assertEquals(OnRejectPolicy.SKIP, OnRejectPolicy.fromString("garbage"));
        assertEquals(OnRejectPolicy.SKIP, OnRejectPolicy.fromString(""));
    }
}
