package com.operativus.agentmanager.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.core.model.enums.OnErrorPolicy;
import com.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import com.operativus.agentmanager.core.model.enums.OnTimeoutPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips {@link HumanReview} through Jackson — same shape that lands in
 * each of {@code workflow_steps.human_review}, {@code agents.human_review},
 * and {@code team_members.human_review} JSONB columns via Hibernate's
 * {@code @JdbcTypeCode(SqlTypes.JSON)}. Any field-name drift surfaces here
 * before it reaches a DB write.
 *
 * <p>Also covers the defensive accessor defaults — {@code effectiveOnReject()},
 * {@code effectiveOnTimeout()}, {@code effectiveOnError()} — which the
 * dispatcher relies on for null-safe policy reads.
 */
class HumanReviewTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTrip_fullyPopulated_preservesAllFields() throws Exception {
        HumanReview hr = new HumanReview(
                true, false, false,
                OnRejectPolicy.ELSE_BRANCH,
                OnTimeoutPolicy.AUTO_REJECT,
                OnErrorPolicy.CANCEL,
                300L,
                List.of("alice", "bob"),
                "step-else-target");

        String json = mapper.writeValueAsString(hr);
        HumanReview parsed = mapper.readValue(json, HumanReview.class);

        assertEquals(true, parsed.requiresConfirmation());
        assertEquals(false, parsed.requiresUserInput());
        assertEquals(false, parsed.requiresOutputReview());
        assertEquals(OnRejectPolicy.ELSE_BRANCH, parsed.onReject());
        assertEquals(OnTimeoutPolicy.AUTO_REJECT, parsed.onTimeout());
        assertEquals(OnErrorPolicy.CANCEL, parsed.onError());
        assertEquals(300L, parsed.timeoutSeconds());
        assertEquals(List.of("alice", "bob"), parsed.approvers());
        assertEquals("step-else-target", parsed.elseStepId());
    }

    @Test
    void roundTrip_mostlyNull_preservesAllNulls() throws Exception {
        // Operators often attach a HumanReview that only sets one or two fields —
        // the rest stay null. JSONB must round-trip nulls cleanly.
        HumanReview hr = new HumanReview(true, null, null, null, null, null, null, null, null);
        String json = mapper.writeValueAsString(hr);
        HumanReview parsed = mapper.readValue(json, HumanReview.class);

        assertEquals(true, parsed.requiresConfirmation());
        assertNull(parsed.requiresUserInput());
        assertNull(parsed.requiresOutputReview());
        assertNull(parsed.onReject());
        assertNull(parsed.onTimeout());
        assertNull(parsed.onError());
        assertNull(parsed.timeoutSeconds());
        assertNull(parsed.approvers());
        assertNull(parsed.elseStepId());
    }

    @Test
    void roundTrip_unknownField_ignored() throws Exception {
        // Future Composio-style upstream additions or hand-edited DB rows must
        // not fail deserialization. JsonIgnoreProperties(ignoreUnknown=true) on
        // the record drives this contract.
        String json = "{\"requiresConfirmation\":true,\"futureField\":\"surprise!\"}";
        HumanReview parsed = mapper.readValue(json, HumanReview.class);
        assertEquals(true, parsed.requiresConfirmation());
    }

    // --- isPauseActive() helper ---

    @Test
    void isPauseActive_anyRequiresFlagTrue_returnsTrue() {
        assertTrue(new HumanReview(true, null, null, null, null, null, null, null, null).isPauseActive());
        assertTrue(new HumanReview(null, true, null, null, null, null, null, null, null).isPauseActive());
        assertTrue(new HumanReview(null, null, true, null, null, null, null, null, null).isPauseActive());
    }

    @Test
    void isPauseActive_allRequiresFlagsFalseOrNull_returnsFalse() {
        assertFalse(new HumanReview(false, false, false, null, null, null, null, null, null).isPauseActive());
        assertFalse(new HumanReview(null, null, null, null, null, null, null, null, null).isPauseActive());
        // Policy-only HumanReview (just on_reject configured) doesn't pause.
        assertFalse(new HumanReview(null, null, null, OnRejectPolicy.CANCEL, null, null, null, null, null)
                .isPauseActive());
    }

    // --- effective* defensive defaults ---

    @Test
    void effectiveOnReject_nullDefaultsToSkip() {
        assertEquals(OnRejectPolicy.SKIP,
                new HumanReview(null, null, null, null, null, null, null, null, null).effectiveOnReject());
    }

    @Test
    void effectiveOnReject_nonNullPassesThrough() {
        assertEquals(OnRejectPolicy.CANCEL,
                new HumanReview(null, null, null, OnRejectPolicy.CANCEL, null, null, null, null, null)
                        .effectiveOnReject());
    }

    @Test
    void effectiveOnTimeout_nullDefaultsToAutoReject() {
        // Per §5 D4 of the unification plan — safe-by-default in AGM's posture
        // (divergence from Agno's AUTO_APPROVE default).
        assertEquals(OnTimeoutPolicy.AUTO_REJECT,
                new HumanReview(null, null, null, null, null, null, null, null, null).effectiveOnTimeout());
    }

    @Test
    void effectiveOnError_nullDefaultsToCancel() {
        assertEquals(OnErrorPolicy.CANCEL,
                new HumanReview(null, null, null, null, null, null, null, null, null).effectiveOnError());
    }
}
