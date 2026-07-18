package ai.operativus.agentmanager.core.exception;

import ai.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: pin the public contract for {@link FinOpsBudgetExhaustedException}
 *   → HTTP 402 Payment Required mapping (SDD agm-finops-cost-enforcement-36 G3).
 *
 * <p>This test is the contract guard for integration clients. Drift in any of:
 * <ul>
 *   <li>status code (must be 402, not 429 or 500)</li>
 *   <li>content type (must be {@code application/problem+json})</li>
 *   <li>body fields {@code cumulativeUsd}, {@code budgetCeilingUsd}, {@code sessionId},
 *       {@code agentId}, {@code runId}, {@code modelId}</li>
 * </ul>
 * is a BREAKING CHANGE for clients. Do not loosen these assertions without a public
 * contract review.
 *
 * <p>Why 402 and not 429: budget exhaustion in this session does not recover by retrying —
 * the only successful resolutions end the session, raise the ceiling, or route through a
 * different budget. 429 invites client auto-retry loops that burn additional tokens
 * without ever succeeding. See SDD §10.1.
 *
 * State: Stateless (handler is a stateless ControllerAdvice).
 */
class GlobalExceptionHandlerFinOpsTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleFinOpsBudgetExhausted_returns402WithProblemDetailAndAllFields() {
        FinOpsBudgetExhaustedException ex = new FinOpsBudgetExhaustedException(
                "session-abc", "agent-xyz", "run-001", "gpt-4o", 12.7500, 10.0000);

        ResponseEntity<ProblemDetail> response = handler.handleFinOpsBudgetExhausted(ex);

        assertThat(response.getStatusCode())
                .as("MUST be 402 Payment Required, NOT 429 — see SDD §10.1")
                .isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(402);
        assertThat(body.getTitle()).isEqualTo("Budget Exhausted");
        assertThat(body.getType()).isEqualTo(URI.create("urn:problem-type:finops-budget-exhausted"));
        assertThat(body.getDetail())
                .as("operator-facing message must steer clients away from auto-retry")
                .contains("Retrying will not succeed");

        assertThat(body.getProperties())
                .as("structured fields are the integration contract — every key must be present")
                .containsKeys("sessionId", "agentId", "runId", "modelId", "cumulativeUsd", "budgetCeilingUsd");
        assertThat(body.getProperties().get("sessionId")).isEqualTo("session-abc");
        assertThat(body.getProperties().get("agentId")).isEqualTo("agent-xyz");
        assertThat(body.getProperties().get("runId")).isEqualTo("run-001");
        assertThat(body.getProperties().get("modelId")).isEqualTo("gpt-4o");
        assertThat(body.getProperties().get("cumulativeUsd")).isEqualTo(12.7500);
        assertThat(body.getProperties().get("budgetCeilingUsd")).isEqualTo(10.0000);
    }

    @Test
    void handleFinOpsBudgetExhausted_passesThroughNullExecutionContext() {
        // The exception type allows null sessionId / agentId / runId for paths where context
        // is partially unbound (e.g. test fixtures, the embedding-cost shortcut). The handler
        // must NOT crash on these — null fields propagate to the JSON body as `null`, which
        // ProblemDetail.setProperty handles natively.
        FinOpsBudgetExhaustedException ex = new FinOpsBudgetExhaustedException(
                null, null, null, "claude-3-5-sonnet", 5.50, 5.00);

        ResponseEntity<ProblemDetail> response = handler.handleFinOpsBudgetExhausted(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties().get("sessionId")).isNull();
        assertThat(body.getProperties().get("agentId")).isNull();
        assertThat(body.getProperties().get("modelId")).isEqualTo("claude-3-5-sonnet");
        assertThat(body.getProperties().get("cumulativeUsd")).isEqualTo(5.50);
    }
}
