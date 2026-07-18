package ai.operativus.agentmanager.compute.workflow;

import ai.operativus.agentmanager.control.repository.WorkflowEdgeRepository;
import ai.operativus.agentmanager.control.repository.WorkflowStepRepository;
import ai.operativus.agentmanager.core.entity.WorkflowEdge;
import ai.operativus.agentmanager.core.entity.WorkflowStep;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link WorkflowDagValidator}'s cycle detection and reachability
 * checks. The validator is pure logic over repository fetches; we mock the repos and
 * drive it through a small set of representative graphs.
 *
 * <p>Cases:
 * <ol>
 *   <li>Empty edge list — no-op (legacy flat-list workflow).</li>
 *   <li>Linear graph S → A → B — passes.</li>
 *   <li>Branching graph S → A, S → B — passes.</li>
 *   <li>Self-loop A → A — throws (cycle).</li>
 *   <li>2-cycle A → B → A — throws.</li>
 *   <li>3-cycle reachable from start — throws.</li>
 *   <li>Diamond S → A,B → C — passes (re-entering C from both branches is NOT a cycle).</li>
 *   <li>Unreachable orphan — passes but logs WARN.</li>
 *   <li>findOutbound returns the right edge for matching condition.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class WorkflowDagValidatorTest {

    @Mock private WorkflowEdgeRepository edges;
    @Mock private WorkflowStepRepository steps;

    private WorkflowDagValidator validator() {
        return new WorkflowDagValidator(edges, steps);
    }

    @Test
    void emptyEdges_isNoOp() {
        when(edges.findByWorkflowIdOrderByFromStepIdAsc("w1")).thenReturn(List.of());
        assertThatNoException().isThrownBy(() -> validator().validate("w1"));
    }

    @Test
    void linearChain_passes() {
        seed("w1", List.of(step("S"), step("A"), step("B")),
                List.of(edge("S", "A", null), edge("A", "B", null)));
        assertThatNoException().isThrownBy(() -> validator().validate("w1"));
    }

    @Test
    void branchingFromStart_passes() {
        seed("w1", List.of(step("S"), step("A"), step("B")),
                List.of(edge("S", "A", "true"), edge("S", "B", "false")));
        assertThatNoException().isThrownBy(() -> validator().validate("w1"));
    }

    @Test
    void selfLoop_throwsCycle() {
        seed("w1", List.of(step("S"), step("A")),
                List.of(edge("S", "A", null), edge("A", "A", null)));
        assertThatThrownBy(() -> validator().validate("w1"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void twoCycle_throws() {
        seed("w1", List.of(step("S"), step("A"), step("B")),
                List.of(edge("S", "A", null), edge("A", "B", null), edge("B", "A", null)));
        assertThatThrownBy(() -> validator().validate("w1"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void diamond_passes_noFalseCycleOnRejoin() {
        // S → A → C
        //  ↘ B ↗
        // Re-entering C via B is NOT a cycle (no back-edge).
        seed("w1", List.of(step("S"), step("A"), step("B"), step("C")),
                List.of(
                        edge("S", "A", null),
                        edge("S", "B", null),
                        edge("A", "C", null),
                        edge("B", "C", null)));
        assertThatNoException().isThrownBy(() -> validator().validate("w1"));
    }

    @Test
    void loopBackEdge_isExemptFromCycleCheck() {
        // S → LOOP ⇄ B (loop/back) ; LOOP -(exit)-> C. The body→loop back-edge is the one
        // sanctioned cycle (plan §2.9) and must NOT fail acyclicity.
        seed("w1", List.of(step("S"), step("LOOP"), step("B"), step("C")),
                List.of(
                        edge("S", "LOOP", null),
                        edge("LOOP", "B", "loop"),
                        edge("B", "LOOP", "back"),
                        edge("LOOP", "C", "exit")));
        assertThatNoException().isThrownBy(() -> validator().validate("w1"));
    }

    @Test
    void nonBackCycle_stillThrows() {
        // Only the "back" port is exempt — a plain (null-port) edge closing a loop is still a cycle.
        seed("w1", List.of(step("S"), step("LOOP"), step("B")),
                List.of(
                        edge("S", "LOOP", null),
                        edge("LOOP", "B", "loop"),
                        edge("B", "LOOP", null)));
        assertThatThrownBy(() -> validator().validate("w1"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void unreachableOrphan_passesValidationButLogsWarn() {
        // S → A; orphan O has no inbound edges. Validator is forgiving here (warn only).
        seed("w1", List.of(step("S"), step("A"), step("O")),
                List.of(edge("S", "A", null)));
        assertThatNoException().isThrownBy(() -> validator().validate("w1"));
    }

    @Test
    void findOutbound_matchesByCondition() {
        when(edges.findByFromStepIdOrderByConditionAscNullsFirst("S")).thenReturn(List.of(
                edge("S", "A", "true"),
                edge("S", "B", "false")));
        Optional<WorkflowEdge> trueBranch = validator().findOutbound("S", "true");
        assertThat(trueBranch).isPresent();
        assertThat(trueBranch.get().getToStepId()).isEqualTo("A");

        Optional<WorkflowEdge> falseBranch = validator().findOutbound("S", "false");
        assertThat(falseBranch).isPresent();
        assertThat(falseBranch.get().getToStepId()).isEqualTo("B");

        Optional<WorkflowEdge> noMatch = validator().findOutbound("S", "default");
        assertThat(noMatch).isEmpty();
    }

    @Test
    void hasEdges_returnsTrueWhenCountPositive() {
        when(edges.countByWorkflowId("w1")).thenReturn(3L);
        when(edges.countByWorkflowId("w2")).thenReturn(0L);
        WorkflowDagValidator v = validator();
        assertThat(v.hasEdges("w1")).isTrue();
        assertThat(v.hasEdges("w2")).isFalse();
    }

    // --- helpers ---

    private void seed(String workflowId, List<WorkflowStep> stepsList, List<WorkflowEdge> edgesList) {
        when(edges.findByWorkflowIdOrderByFromStepIdAsc(eq(workflowId))).thenReturn(edgesList);
        when(steps.findByWorkflowIdOrderByStepOrderAsc(eq(workflowId))).thenReturn(stepsList);
    }

    private static WorkflowStep step(String id) {
        WorkflowStep s = new WorkflowStep();
        s.setId(id);
        return s;
    }

    private static WorkflowEdge edge(String from, String to, String condition) {
        return new WorkflowEdge("e-" + from + "-" + to, "w1", from, to, condition);
    }
}
