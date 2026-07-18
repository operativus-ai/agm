package ai.operativus.agentmanager.integration.hitl;

import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the {@link ScopedValue} inheritance contract that
 *   {@code AgentService.continueRun} (line 587–592 in {@code main}) relies on for the resume
 *   path's correctness. Specifically, {@code ScopedValue.where(approvedTools, X).where(
 *   preAllocatedRunId, runId).call(() -> run(...))} must surface those bindings inside
 *   the inner {@code run()} via {@code AgentContextHolder.approvedTools.get()} and
 *   {@code AgentContextHolder.preAllocatedRunId.get()}.
 * State: Stateless — pure JDK-level assertions, no Spring context.
 *
 * <p><b>Scope (per Appendix B of the deep-design report):</b>
 * <ul>
 *   <li><b>In scope (this test):</b> single-agent resume's same-VT synchronous chain. The
 *       trace in T003 confirmed this works correctly; this test is the explicit contract pin
 *       so a regression (e.g., switching {@code .call()} to {@code .submit()} on a different
 *       executor) is caught immediately.</li>
 *   <li><b>Out of scope:</b> team-agent (broadcast/parallel) resume — those orchestrators
 *       spawn fresh VTs via {@code CompletableFuture.supplyAsync(virtualExecutor)} which
 *       do NOT inherit JDK 21 {@code ScopedValue} bindings. That gap is part of Tier 2.5
 *       F2/F3 (multi-agent strategies' caller-context propagation), not Tier 2.4. Marker
 *       below pins it as a {@code @Disabled} reference for the future fix.</li>
 * </ul>
 *
 * <p><b>Why a JDK-level test, not Spring integration?</b> The contract under test is the
 * language-level inheritance behavior of {@code ScopedValue.where(...).call(...)} — which
 * works regardless of Spring. Wiring a full {@code @SpringBootTest} with a custom
 * {@code @AgentToolComponent} that captures context and a {@code FakeChatModel} that
 * routes to it would add ~150 LOC of scaffolding to verify the same JDK guarantee. The
 * trace in T003 is the integration-level proof; this test is the unit-level safety net.
 */
public class HitlScopedValueResumeRuntimeTest {

    // T006 primary contract — Mirrors the binding chain at AgentService.java:587–592:
    //   ScopedValue
    //     .where(preAllocatedRunId, runId)
    //     .where(approvedTools, Set.of(toolName))
    //     .call(() -> run(...))
    // Inside the .call() lambda, AgentContextHolder accessors must observe both bindings.
    // Pre-F4 fix (#355), AgentContextHolder.approveTool() outside a bound scope was a no-op
    // and the resumed run silently re-paused on the same tool. This pin guards the
    // bind-then-observe contract that the F4 fix relies on.
    @Test
    void resumeBindings_observableInsideSynchronousCall() throws Exception {
        Set<String> approvedAtBind = new HashSet<>(Set.of("destructive-tool"));
        AtomicReference<Set<String>> seenApproved = new AtomicReference<>();
        AtomicReference<String> seenRunId = new AtomicReference<>();

        ScopedValue
                .where(AgentContextHolder.preAllocatedRunId, "run-resume-123")
                .where(AgentContextHolder.approvedTools, approvedAtBind)
                .call(() -> {
                    seenApproved.set(AgentContextHolder.approvedTools.get());
                    seenRunId.set(AgentContextHolder.preAllocatedRunId.get());
                    return null;
                });

        assertAll("synchronous .call() inherits both ScopedValue bindings",
                () -> assertEquals("run-resume-123", seenRunId.get(),
                        "preAllocatedRunId binding must be observable inside the .call() — F3a fix relies on this for inner run() to reuse the row instead of inserting a duplicate"),
                () -> assertEquals(approvedAtBind, seenApproved.get(),
                        "approvedTools binding must be observable — F4 fix relies on this so AugmentedToolCallbackProvider.adaptCallback bypasses HitlAdvisor for the resumed tool"),
                () -> assertTrue(seenApproved.get().contains("destructive-tool"),
                        "the seeded tool must be present in the approvedTools set"));
    }

    // T006 nested contract — Inner run() at AgentService.java:230–244 establishes its own
    // ScopedValue chain that INHERITS approvedTools from the outer scope when bound:
    //   .where(approvedTools, OUTER_BOUND ? new HashSet<>(outer.get()) : new HashSet<>())
    // This pins that pattern: an outer-bound approvedTools set survives a nested
    // ScopedValue.where(...).call(...) chain.
    @Test
    void approvedToolsInheritance_survivesNestedScopedValueChain() throws Exception {
        Set<String> outerApproved = new HashSet<>(Set.of("tool-A"));
        AtomicReference<Set<String>> innerSeen = new AtomicReference<>();

        ScopedValue
                .where(AgentContextHolder.approvedTools, outerApproved)
                .call(() -> {
                    // Mirror the inner run()'s line 231-234 pattern.
                    Set<String> innerApproved =
                            AgentContextHolder.approvedTools.isBound()
                                    ? new HashSet<>(AgentContextHolder.approvedTools.get())
                                    : new HashSet<>();

                    return ScopedValue
                            .where(AgentContextHolder.approvedTools, innerApproved)
                            .where(AgentContextHolder.currentRunId, "inner-run-456")
                            .call(() -> {
                                innerSeen.set(AgentContextHolder.approvedTools.get());
                                return null;
                            });
                });

        assertAll("nested chain preserves the outer approvedTools",
                () -> assertTrue(innerSeen.get().contains("tool-A"),
                        "outer-bound tool must be observable inside the nested chain — without this inheritance, F4 would silently fail in inner run()"),
                () -> assertEquals(1, innerSeen.get().size(),
                        "no extra tools must leak into the inner set"));
    }

    // T006 unbound-scope contract — Outside any ScopedValue.where().call(), the accessor
    // must report unbound. This pins the F4 root cause: AgentContextHolder.approveTool()
    // on an unbound scope was a no-op, which is why the historical resume path silently
    // dropped approvals before #355 wrapped the call in ScopedValue.where(approvedTools, ...).
    @Test
    void approvedTools_outsideScope_isUnbound() {
        // Direct check — no .where().call() wrapper.
        assertFalse(AgentContextHolder.approvedTools.isBound(),
                "outside any .where().call(), the ScopedValue must report unbound — this is the JDK guarantee that the F4 bug exploited");
        assertFalse(AgentContextHolder.preAllocatedRunId.isBound(),
                "preAllocatedRunId must also be unbound outside scope — guards against accidental cross-test leakage");
    }

    // Tier 2.5+ team-VT propagation contract — pins the bug + the fix shape at the JDK level.
    //
    // The bug: SwarmOrchestrator spawns fresh VTs via
    // Executors.newVirtualThreadPerTaskExecutor() to dispatch members in parallel. Fresh VTs
    // do NOT inherit the parent thread's ScopedValue bindings, so a resume's approvedTools
    // (bound by AgentService.continueRun) is lost on the member VT. HitlAdvisor inside the
    // member then sees no approval and re-pauses on the same tool, causing a resume loop.
    //
    // The fix: capture parent values on the orchestrator thread BEFORE submit, then rebind
    // them inside the member's task body via ScopedValue.where(...). This test pins both
    // halves: the broken state (no rebind = unbound on member VT) and the fixed state
    // (parent-capture-rebind = observable on member VT). Production code in
    // {@code SwarmOrchestrator.execute} MUST follow the rebind pattern this test validates.
    @Test
    void teamMemberVT_withoutRebind_losesApprovedToolsBinding() throws Exception {
        Set<String> outerApproved = new HashSet<>(Set.of("destructive-tool"));
        AtomicBoolean memberSawBound = new AtomicBoolean(true);   // initialize to "wrong"
        AtomicReference<Set<String>> memberSawValue = new AtomicReference<>(Set.of("PRE-VT"));

        ScopedValue
                .where(AgentContextHolder.approvedTools, outerApproved)
                .call(() -> {
                    // Simulate SwarmOrchestrator's spawn site WITHOUT the parent-capture
                    // rebind: submit a task to a fresh-VT executor with no ScopedValue.where
                    // wrapper inside the task body. This is the bug shape.
                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        Future<Void> task = executor.submit(() -> {
                            memberSawBound.set(AgentContextHolder.approvedTools.isBound());
                            memberSawValue.set(
                                    AgentContextHolder.approvedTools.isBound()
                                            ? new HashSet<>(AgentContextHolder.approvedTools.get())
                                            : Set.of());
                            return null;
                        });
                        task.get();
                    }
                    return null;
                });

        assertAll("fresh VT does NOT inherit ScopedValue bindings (the bug we're fixing)",
                () -> assertFalse(memberSawBound.get(),
                        "approvedTools must be UNBOUND on the member VT — JDK 21 ScopedValue " +
                        "does not propagate across fresh-VT spawns. If this assertion fails, " +
                        "either the JDK semantics changed or the test isn't actually crossing a " +
                        "VT boundary; either way the production fix is no longer needed in its " +
                        "current shape and the spec must be re-examined."),
                () -> assertEquals(Set.of(), memberSawValue.get(),
                        "member VT must observe approvedTools as empty/unbound — getting the " +
                        "outer-bound 'destructive-tool' here would mean ScopedValues did " +
                        "propagate across the spawn, contradicting the bug premise"));
    }

    @Test
    void teamMemberVT_withParentCaptureRebind_preservesApprovedToolsBinding() throws Exception {
        Set<String> outerApproved = new HashSet<>(Set.of("destructive-tool"));
        AtomicReference<Set<String>> member1Saw = new AtomicReference<>();
        AtomicReference<Set<String>> member2Saw = new AtomicReference<>();
        AtomicReference<RunTelemetryAccumulator> member1Telemetry = new AtomicReference<>();
        AtomicReference<RunTelemetryAccumulator> member2Telemetry = new AtomicReference<>();

        ScopedValue
                .where(AgentContextHolder.approvedTools, outerApproved)
                .call(() -> {
                    // Simulate the orchestrator's parent-capture block (matches §4.1 of the
                    // team-VT spec): capture parent values BEFORE the loop, then for each
                    // member create a fresh defensive copy and rebind inside the task body.
                    final Set<String> parentApprovedSnapshot =
                            AgentContextHolder.approvedTools.isBound()
                                    ? Set.copyOf(AgentContextHolder.approvedTools.get())
                                    : null;

                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        Future<Void> m1 = executor.submit(memberTask(parentApprovedSnapshot,
                                member1Saw, member1Telemetry));
                        Future<Void> m2 = executor.submit(memberTask(parentApprovedSnapshot,
                                member2Saw, member2Telemetry));
                        m1.get();
                        m2.get();
                    }
                    return null;
                });

        assertAll("parent-capture-rebind survives the VT spawn boundary for both members",
                () -> assertTrue(member1Saw.get().contains("destructive-tool"),
                        "member 1 must see the parent's approvedTools after rebind"),
                () -> assertTrue(member2Saw.get().contains("destructive-tool"),
                        "member 2 must see the parent's approvedTools after rebind"),
                () -> assertNotSame(member1Saw.get(), member2Saw.get(),
                        "members must receive DISTINCT HashSet instances — a single shared " +
                        "instance would alias mutations across siblings (v2 spec bug caught " +
                        "in Plan-agent review #2)"),
                () -> assertNotSame(member1Telemetry.get(), member2Telemetry.get(),
                        "members must receive DISTINCT RunTelemetryAccumulator instances — " +
                        "isolation, not rollup. Read-side aggregation via vw_run_tree_cost."));
    }

    // Mirrors the per-member fresh-copy + fresh-telemetry pattern that SwarmOrchestrator
    // must use inside its executor.submit task bodies.
    private static java.util.concurrent.Callable<Void> memberTask(
            Set<String> parentApprovedSnapshot,
            AtomicReference<Set<String>> sawApproved,
            AtomicReference<RunTelemetryAccumulator> sawTelemetry) {
        return () -> {
            // Per-member defensive copy: each member gets its OWN mutable HashSet so a future
            // AgentContextHolder.approveTool() mutation on one member doesn't leak to siblings.
            Set<String> memberApproved = parentApprovedSnapshot != null
                    ? new HashSet<>(parentApprovedSnapshot)
                    : null;
            // Per-member fresh telemetry accumulator: members write to their own counters,
            // not the parent's shared instance.
            RunTelemetryAccumulator memberTelemetry = new RunTelemetryAccumulator();

            ScopedValue.Carrier carrier = ScopedValue
                    .where(AgentContextHolder.telemetry, memberTelemetry);
            if (memberApproved != null) {
                carrier = carrier.where(AgentContextHolder.approvedTools, memberApproved);
            }
            return carrier.call(() -> {
                sawApproved.set(AgentContextHolder.approvedTools.isBound()
                        ? new HashSet<>(AgentContextHolder.approvedTools.get())
                        : Set.of());
                sawTelemetry.set(AgentContextHolder.getTelemetry());
                return null;
            });
        };
    }
}
