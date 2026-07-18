package com.operativus.agentmanager.core.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RunStatus {
    PENDING("PENDING"),
    QUEUED("QUEUED"),
    RUNNING("RUNNING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    EXPIRED("EXPIRED"),
    PAUSED("PAUSED"),
    /**
     * REQ-DR-4 (Workflow Router step): the workflow is suspended at a ROUTER step
     * waiting on an external selector decision. Distinct from {@link #PAUSED}
     * (HITL approval on a tool call) so the resume endpoint can dispatch on the
     * exact reason. The follow-up PR that implements the full router-selector
     * pipeline (RULE / LLM / HITL) transitions runs into and out of this state.
     */
    AWAITING_ROUTE_SELECTION("AWAITING_ROUTE_SELECTION"),
    /**
     * REQ-HR-2 (HumanReview unification): the run is suspended pending a unified
     * HumanReview decision. Replaces {@link #PAUSED} and {@link #AWAITING_ROUTE_SELECTION}
     * in the post-REQ-HR-3..6 dispatcher integration; both old values remain valid
     * during the migration window so existing pending rows don't get orphaned.
     * See {@code docs/analysis/agm-human-review-unification.md} §5 D2.
     */
    AWAITING_HUMAN_REVIEW("AWAITING_HUMAN_REVIEW"),
    PROCESSING("PROCESSING"),
    CREATED("CREATED"),
    CANCELLED("CANCELLED");

    private final String value;

    RunStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RunStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        for (RunStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown status type: " + value);
    }
}
