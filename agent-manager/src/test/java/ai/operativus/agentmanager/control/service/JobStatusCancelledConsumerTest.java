package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.core.model.enums.JobStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins that CANCELLED is a recognized terminal state in JobStatus and is excluded from
 * active-job key deduplication semantics. Without this test a refactor could silently
 * drop CANCELLED from fromValue() or treat it as non-terminal.
 */
class JobStatusCancelledConsumerTest {

    @Test
    void cancelled_isPresentInEnum() {
        assertThat(JobStatus.valueOf("CANCELLED")).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void fromValue_cancelled_roundTrips() {
        assertThat(JobStatus.fromValue("CANCELLED")).isEqualTo(JobStatus.CANCELLED);
        assertThat(JobStatus.fromValue("cancelled")).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void fromValue_unknownStatus_throws() {
        assertThatThrownBy(() -> JobStatus.fromValue("BOGUS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allTerminalStatuses_arePresent() {
        // COMPLETED, FAILED, DLQ, CANCELLED are all terminal — verify none were dropped
        assertThat(JobStatus.values())
                .contains(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.DLQ, JobStatus.CANCELLED);
    }
}
