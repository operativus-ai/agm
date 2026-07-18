package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.core.model.RunCostNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunCostTreeServiceTest {

    @Mock private RunRepository runRepository;
    private MeterRegistry meterRegistry;
    private RunCostTreeService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new RunCostTreeService(runRepository, meterRegistry);
    }

    @Test
    void getCostTree_AssemblesNestedStructureFromFlatRows() {
        // Flat CTE rows — root + one child + one grandchild. CTE returns depth ASC,
        // id ASC, so the service must group by parent_run_id to link them.
        List<Object[]> rows = List.of(
                row("root", null, "agent-root", 0, 100L, 200L, new BigDecimal("1.50")),
                row("child", "root", "agent-child", 1, 10L, 20L, new BigDecimal("0.30")),
                row("gc", "child", "agent-gc", 2, 1L, 2L, new BigDecimal("0.05")));
        when(runRepository.findRunCostTree("root", 10)).thenReturn(rows);

        Optional<RunCostNode> tree = service.getCostTree("root", 10);

        assertThat(tree).isPresent();
        RunCostNode root = tree.get();
        assertThat(root.id()).isEqualTo("root");
        assertThat(root.subRuns()).hasSize(1);
        assertThat(root.subRuns().get(0).id()).isEqualTo("child");
        assertThat(root.subRuns().get(0).subRuns()).hasSize(1);
        assertThat(root.subRuns().get(0).subRuns().get(0).id()).isEqualTo("gc");
        assertThat(root.subRuns().get(0).subRuns().get(0).depth()).isEqualTo(2);
    }

    @Test
    void getCostTree_UnknownRoot_ReturnsEmptyOptional() {
        when(runRepository.findRunCostTree("missing", 10)).thenReturn(List.of());

        assertThat(service.getCostTree("missing", 10)).isEmpty();
    }

    @Test
    void getCostTree_MaxDepthZero_ReturnsRootWithNoSubRuns() {
        when(runRepository.findRunCostTree("root", 0)).thenReturn(List.<Object[]>of(
                row("root", null, "agent-a", 0, 5L, 10L, new BigDecimal("0.10"))));

        Optional<RunCostNode> tree = service.getCostTree("root", 0);

        assertThat(tree).isPresent();
        assertThat(tree.get().subRuns()).isEmpty();
    }

    @Test
    void getCostTree_NullEnrichedColumnsPropagate() {
        // Runs created before the telemetry accumulator started flushing have null
        // token / cost columns. CTE emits them as null — service must pass through.
        when(runRepository.findRunCostTree("root", 10)).thenReturn(List.<Object[]>of(
                row("root", null, "agent-a", 0, null, null, null)));

        RunCostNode node = service.getCostTree("root", 10).orElseThrow();

        assertThat(node.inputTokens()).isNull();
        assertThat(node.outputTokens()).isNull();
        assertThat(node.totalCostUsd()).isNull();
    }

    @Test
    void getCostTree_EmitsTimerTaggedWithDeepestDepth() {
        when(runRepository.findRunCostTree("root", 10)).thenReturn(List.of(
                row("root", null, "agent-a", 0, null, null, null),
                row("child", "root", "agent-b", 1, null, null, null),
                row("gc", "child", "agent-c", 2, null, null, null)));

        service.getCostTree("root", 10);

        // Timer registered with depth_reached = 2 (not 0 or 10).
        assertThat(meterRegistry.get("agm.observability.run_cost.tree")
                .tag("depth_reached", "2").timer().count()).isEqualTo(1L);
    }

    @Test
    void getCostTree_MultipleChildrenUnderSameParent_PreservedInOrder() {
        when(runRepository.findRunCostTree("root", 10)).thenReturn(List.of(
                row("root", null, "agent-a", 0, null, null, null),
                row("child-1", "root", "agent-b", 1, null, null, null),
                row("child-2", "root", "agent-c", 1, null, null, null)));

        RunCostNode tree = service.getCostTree("root", 10).orElseThrow();

        assertThat(tree.subRuns()).hasSize(2);
        assertThat(tree.subRuns().get(0).id()).isEqualTo("child-1");
        assertThat(tree.subRuns().get(1).id()).isEqualTo("child-2");
    }

    private static Object[] row(String id, String parentId, String agentId, int depth,
                                Long inputTokens, Long outputTokens, BigDecimal cost) {
        return new Object[]{id, parentId, agentId, depth, inputTokens, outputTokens, cost};
    }
}
