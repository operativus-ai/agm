package ai.operativus.agentmanager.core.model;

import ai.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link HumanReviewValidator#validate} combo constraints — every invalid
 * combination produces a human-readable error message, every valid one passes
 * silently.
 */
class HumanReviewValidatorTest {

    @Test
    void validate_null_returnsSilently() {
        assertDoesNotThrow(() -> HumanReviewValidator.validate(null));
    }

    @Test
    void validate_emptyHumanReview_returnsSilently() {
        // All fields null is the "operator set the column but configured nothing"
        // state — valid; dispatcher will see effective* defaults at read time.
        assertDoesNotThrow(() -> HumanReviewValidator.validate(
                new HumanReview(null, null, null, null, null, null, null, null, null)));
    }

    // --- mutual exclusion of requires* flags ---

    @Test
    void validate_singleRequiresFlag_isValid() {
        assertDoesNotThrow(() -> HumanReviewValidator.validate(
                new HumanReview(true, null, null, null, null, null, null, null, null)));
        assertDoesNotThrow(() -> HumanReviewValidator.validate(
                new HumanReview(null, true, null, null, null, null, null, null, null)));
        assertDoesNotThrow(() -> HumanReviewValidator.validate(
                new HumanReview(null, null, true, null, null, null, null, null, null)));
    }

    @Test
    void validate_twoRequiresFlagsActive_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HumanReviewValidator.validate(
                        new HumanReview(true, true, null, null, null, null, null, null, null)));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("at most one of"),
                "expected mutual-exclusion error; got: " + ex.getMessage());
    }

    @Test
    void validate_allThreeRequiresFlagsActive_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> HumanReviewValidator.validate(
                        new HumanReview(true, true, true, null, null, null, null, null, null)));
    }

    // --- ELSE_BRANCH + elseStepId co-constraint ---

    @Test
    void validate_elseBranchWithoutElseStepId_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HumanReviewValidator.validate(
                        new HumanReview(null, null, null, OnRejectPolicy.ELSE_BRANCH,
                                null, null, null, null, null)));
        assertEquals("HumanReview: onReject=ELSE_BRANCH requires non-blank elseStepId", ex.getMessage());
    }

    @Test
    void validate_elseBranchWithBlankElseStepId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> HumanReviewValidator.validate(
                        new HumanReview(null, null, null, OnRejectPolicy.ELSE_BRANCH,
                                null, null, null, null, "   ")));
    }

    @Test
    void validate_elseBranchWithElseStepId_succeeds() {
        assertDoesNotThrow(() -> HumanReviewValidator.validate(
                new HumanReview(null, null, null, OnRejectPolicy.ELSE_BRANCH,
                        null, null, null, null, "step-target")));
    }

    @Test
    void validate_elseStepIdWithoutElseBranchPolicy_throws() {
        // SKIP + elseStepId is meaningless and rejected — same rule as the
        // existing WorkflowService.validateElseStepId on the legacy column.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HumanReviewValidator.validate(
                        new HumanReview(null, null, null, OnRejectPolicy.SKIP,
                                null, null, null, null, "step-orphan")));
        assertEquals("HumanReview: elseStepId is only valid when onReject=ELSE_BRANCH", ex.getMessage());
    }

    // --- timeoutSeconds positive constraint ---

    @Test
    void validate_nullTimeout_isValid() {
        assertDoesNotThrow(() -> HumanReviewValidator.validate(
                new HumanReview(null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void validate_positiveTimeout_isValid() {
        assertDoesNotThrow(() -> HumanReviewValidator.validate(
                new HumanReview(null, null, null, null, null, null, 60L, null, null)));
    }

    @Test
    void validate_zeroTimeout_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> HumanReviewValidator.validate(
                        new HumanReview(null, null, null, null, null, null, 0L, null, null)));
    }

    @Test
    void validate_negativeTimeout_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> HumanReviewValidator.validate(
                        new HumanReview(null, null, null, null, null, null, -1L, null, null)));
    }

    // --- CANCEL + pause-mode incompatibility (mirrors REQ-DR-6 PR-4) ---

    @Test
    void validate_cancelWithRequiresConfirmation_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HumanReviewValidator.validate(
                        new HumanReview(true, null, null, OnRejectPolicy.CANCEL,
                                null, null, null, null, null)));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("onReject=CANCEL is not allowed with an active pause gate"),
                "expected CANCEL+pause-gate error; got: " + ex.getMessage());
    }

    @Test
    void validate_cancelWithRequiresUserInput_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> HumanReviewValidator.validate(
                        new HumanReview(null, true, null, OnRejectPolicy.CANCEL,
                                null, null, null, null, null)));
    }

    @Test
    void validate_cancelWithRequiresOutputReview_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> HumanReviewValidator.validate(
                        new HumanReview(null, null, true, OnRejectPolicy.CANCEL,
                                null, null, null, null, null)));
    }

    @Test
    void validate_cancelWithoutPauseGate_isValid() {
        // CANCEL with no pause mode is the legitimate router/orchestration
        // shape — "cancel if upstream rejects" without explicit operator pause.
        assertDoesNotThrow(() -> HumanReviewValidator.validate(
                new HumanReview(null, null, null, OnRejectPolicy.CANCEL,
                        null, null, null, null, null)));
    }

    @Test
    void validate_skipWithPauseGate_isValid() {
        // SKIP + pause gate is the canonical "ask, then proceed-or-skip" shape.
        assertDoesNotThrow(() -> HumanReviewValidator.validate(
                new HumanReview(true, null, null, OnRejectPolicy.SKIP,
                        null, null, null, null, null)));
    }
}
