package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pins {@link CanaryRoutingService} routing math + status
 *   reporting. Without these tests, silent breakage of the canary split would
 *   invalidate every A/B experiment shipped through the platform — and nobody
 *   would notice, because the production agent would still serve traffic.
 *
 *   <p>Routing logic is non-trivial:
 *   <ul>
 *     <li>Probabilistic split based on {@code ThreadLocalRandom.current().nextInt(100)}</li>
 *     <li>Multiple canaries pointing to the same base distribute proportionally</li>
 *     <li>Total canary percentage caps at 100 (overflow defended)</li>
 *     <li>Canaries with null or 0 percentage are filtered out</li>
 *     <li>Inactive canaries are filtered out (handled by {@code findByActiveTrue})</li>
 *   </ul>
 *
 *   <p>{@link ThreadLocalRandom#current()} is statically mocked so each test pins
 *   a specific roll value and the resulting routing decision is deterministic.
 *
 * State: Stateless (per-test {@link MockedStatic} scope cleared via try-with-resources).
 */
@ExtendWith(MockitoExtension.class)
public class CanaryRoutingServiceTest {

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final CanaryRoutingService service = new CanaryRoutingService(agentRepository);

    // ════════════════════════════════════════════════════════════════
    // resolveEffectiveAgent — no canaries
    // ════════════════════════════════════════════════════════════════

    @Test
    void resolveEffectiveAgent_noCanariesAtAll_returnsRequestedAgent() {
        when(agentRepository.findByActiveTrue()).thenReturn(List.of());
        assertEquals("base-agent", service.resolveEffectiveAgent("base-agent"));
    }

    @Test
    void resolveEffectiveAgent_canariesForDifferentBase_returnsRequestedAgent() {
        // A canary pointing at "other-agent" must NOT affect routing for "base-agent".
        AgentEntity unrelated = canary("c1", "other-agent", 50);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(unrelated));
        assertEquals("base-agent", service.resolveEffectiveAgent("base-agent"));
    }

    @Test
    void resolveEffectiveAgent_canaryWithNullPercentage_isFilteredOut() {
        AgentEntity nullPct = canary("c1", "base-agent", null);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(nullPct));
        assertEquals("base-agent", service.resolveEffectiveAgent("base-agent"),
                "canary with null percentage must be filtered out, returning base");
    }

    @Test
    void resolveEffectiveAgent_canaryWithZeroPercentage_isFilteredOut() {
        AgentEntity zero = canary("c1", "base-agent", 0);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(zero));
        assertEquals("base-agent", service.resolveEffectiveAgent("base-agent"),
                "canary with 0% must be filtered out (> 0 check), returning base");
    }

    // ════════════════════════════════════════════════════════════════
    // resolveEffectiveAgent — single canary, deterministic roll
    // ════════════════════════════════════════════════════════════════

    @Test
    void resolveEffectiveAgent_50PercentCanary_roll49_routesToCanary() {
        AgentEntity c = canary("c1", "base-agent", 50);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c));
        withRoll(49, () ->
                assertEquals("c1", service.resolveEffectiveAgent("base-agent"),
                        "roll=49 < 50% must route to canary"));
    }

    @Test
    void resolveEffectiveAgent_50PercentCanary_roll50_returnsBase() {
        // Boundary: roll == totalCanaryPercentage falls through to base. The check is
        // `if (roll < totalCanaryPercentage)` — strict less-than.
        AgentEntity c = canary("c1", "base-agent", 50);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c));
        withRoll(50, () ->
                assertEquals("base-agent", service.resolveEffectiveAgent("base-agent"),
                        "roll=50 is NOT < 50 — must fall through to base"));
    }

    @Test
    void resolveEffectiveAgent_100PercentCanary_roll99_routesToCanary() {
        AgentEntity c = canary("c1", "base-agent", 100);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c));
        withRoll(99, () ->
                assertEquals("c1", service.resolveEffectiveAgent("base-agent"),
                        "100% canary at roll=99 must route to canary"));
    }

    @Test
    void resolveEffectiveAgent_100PercentCanary_roll0_routesToCanary() {
        AgentEntity c = canary("c1", "base-agent", 100);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c));
        withRoll(0, () ->
                assertEquals("c1", service.resolveEffectiveAgent("base-agent")));
    }

    // ════════════════════════════════════════════════════════════════
    // resolveEffectiveAgent — multiple canaries, proportional split
    // ════════════════════════════════════════════════════════════════

    @Test
    void resolveEffectiveAgent_twoCanaries_30And20_roll0_picksFirst() {
        AgentEntity c1 = canary("c1", "base-agent", 30);
        AgentEntity c2 = canary("c2", "base-agent", 20);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c1, c2));
        withRoll(0, () ->
                assertEquals("c1", service.resolveEffectiveAgent("base-agent"),
                        "roll=0 lands in first canary's [0,30) slice"));
    }

    @Test
    void resolveEffectiveAgent_twoCanaries_30And20_roll29_picksFirst() {
        AgentEntity c1 = canary("c1", "base-agent", 30);
        AgentEntity c2 = canary("c2", "base-agent", 20);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c1, c2));
        withRoll(29, () ->
                assertEquals("c1", service.resolveEffectiveAgent("base-agent"),
                        "roll=29 is the last value in first canary's [0,30) slice"));
    }

    @Test
    void resolveEffectiveAgent_twoCanaries_30And20_roll30_picksSecond() {
        AgentEntity c1 = canary("c1", "base-agent", 30);
        AgentEntity c2 = canary("c2", "base-agent", 20);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c1, c2));
        withRoll(30, () ->
                assertEquals("c2", service.resolveEffectiveAgent("base-agent"),
                        "roll=30 lands in second canary's [30,50) slice — cumulative pin"));
    }

    @Test
    void resolveEffectiveAgent_twoCanaries_30And20_roll49_picksSecond() {
        AgentEntity c1 = canary("c1", "base-agent", 30);
        AgentEntity c2 = canary("c2", "base-agent", 20);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c1, c2));
        withRoll(49, () ->
                assertEquals("c2", service.resolveEffectiveAgent("base-agent"),
                        "roll=49 is the last value in second canary's [30,50) slice"));
    }

    @Test
    void resolveEffectiveAgent_twoCanaries_30And20_roll50_returnsBase() {
        AgentEntity c1 = canary("c1", "base-agent", 30);
        AgentEntity c2 = canary("c2", "base-agent", 20);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c1, c2));
        withRoll(50, () ->
                assertEquals("base-agent", service.resolveEffectiveAgent("base-agent"),
                        "roll=50 exceeds total canary % (30+20=50) — must return base"));
    }

    // ════════════════════════════════════════════════════════════════
    // resolveEffectiveAgent — overflow cap
    // ════════════════════════════════════════════════════════════════

    @Test
    void resolveEffectiveAgent_canariesTotalOver100_isCappedAt100() {
        // Three canaries at 60 + 30 + 30 = 120 — totalCanaryPercentage caps at 100.
        // At roll=99, must route to a canary (under the cap) instead of base.
        AgentEntity c1 = canary("c1", "base-agent", 60);
        AgentEntity c2 = canary("c2", "base-agent", 30);
        AgentEntity c3 = canary("c3", "base-agent", 30);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c1, c2, c3));
        withRoll(99, () -> {
            String result = service.resolveEffectiveAgent("base-agent");
            // Roll=99 is < cumulative 60+30+30=120 → falls into c3's [90,120) slice
            // (the production code iterates canaries and assigns by cumulative band).
            assertEquals("c3", result,
                    "roll=99 falls into c3's cumulative band [90, 120) — exceeds-100 cap still routes correctly");
        });
    }

    // ════════════════════════════════════════════════════════════════
    // getCanaryStatus — map shape
    // ════════════════════════════════════════════════════════════════

    @Test
    void getCanaryStatus_noCanaries_returnsZeroSplit() {
        when(agentRepository.findByActiveTrue()).thenReturn(List.of());
        Map<String, Object> status = service.getCanaryStatus("base-agent");

        assertEquals("base-agent", status.get("baseAgentId"));
        assertEquals(0, status.get("canaryCount"));
        assertEquals(List.of(), status.get("canaries"));
        assertEquals(0, status.get("totalCanaryPercentage"));
        assertEquals(100, status.get("productionPercentage"));
    }

    @Test
    void getCanaryStatus_singleCanaryAt50_returns50_50Split() {
        AgentEntity c = canary("c1", "base-agent", 50);
        c.setName("v2-experiment");
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c));

        Map<String, Object> status = service.getCanaryStatus("base-agent");

        assertEquals(1, status.get("canaryCount"));
        assertEquals(50, status.get("totalCanaryPercentage"));
        assertEquals(50, status.get("productionPercentage"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> canaries = (List<Map<String, Object>>) status.get("canaries");
        assertEquals(1, canaries.size());
        assertEquals("c1", canaries.get(0).get("id"));
        assertEquals("v2-experiment", canaries.get(0).get("name"));
        assertEquals(50, canaries.get(0).get("percentage"));
    }

    @Test
    void getCanaryStatus_twoCanariesTotal70_returns70_30Split() {
        AgentEntity c1 = canary("c1", "base-agent", 40);
        AgentEntity c2 = canary("c2", "base-agent", 30);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c1, c2));

        Map<String, Object> status = service.getCanaryStatus("base-agent");
        assertEquals(2, status.get("canaryCount"));
        assertEquals(70, status.get("totalCanaryPercentage"));
        assertEquals(30, status.get("productionPercentage"));
    }

    @Test
    void getCanaryStatus_canaryWithNullName_coalesceToIdInsteadOfNpe() {
        // Defensive regression pin for the Map.of(null-value) NPE class. AgentEntity.name
        // is @Column(nullable=false) so persisted canaries always have a name, but the
        // inner map construction must still tolerate null name (transient entities,
        // partial fetches, future schema changes) without taking down the entire status
        // endpoint for a single bad row.
        AgentEntity c = new AgentEntity();
        c.setId("c1");
        // Deliberately do NOT call setName — leaves name == null.
        c.setActive(true);
        c.setCanaryBaseAgentId("base-agent");
        c.setCanaryPercentage(25);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c));

        Map<String, Object> status = service.getCanaryStatus("base-agent");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> canaries = (List<Map<String, Object>>) status.get("canaries");
        assertEquals(1, canaries.size(), "single canary must not be silently dropped");
        assertEquals("c1", canaries.get(0).get("id"));
        assertEquals("c1", canaries.get(0).get("name"),
                "null name must coalesce to id (not be omitted, not NPE the endpoint)");
        assertEquals(25, canaries.get(0).get("percentage"));
    }

    @Test
    void getCanaryStatus_canaryWithNullPercentage_treatedAsZero() {
        AgentEntity c = canary("c1", "base-agent", null);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c));

        Map<String, Object> status = service.getCanaryStatus("base-agent");
        assertEquals(1, status.get("canaryCount"),
                "status includes null-pct canaries (unlike resolveEffectiveAgent which filters them)");
        assertEquals(0, status.get("totalCanaryPercentage"));
        assertEquals(100, status.get("productionPercentage"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> canaries = (List<Map<String, Object>>) status.get("canaries");
        assertEquals(0, canaries.get(0).get("percentage"),
                "null percentage must surface as 0 in the response, not NPE");
    }

    @Test
    void getCanaryStatus_totalOver100_capsProductionAtZero() {
        AgentEntity c1 = canary("c1", "base-agent", 60);
        AgentEntity c2 = canary("c2", "base-agent", 60);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(c1, c2));

        Map<String, Object> status = service.getCanaryStatus("base-agent");
        assertEquals(120, status.get("totalCanaryPercentage"),
                "raw total is reported (no cap on the percentage field itself)");
        assertEquals(0, status.get("productionPercentage"),
                "productionPercentage = 100 - min(total, 100) → caps at 0");
    }

    @Test
    void getCanaryStatus_excludesCanariesForOtherBaseAgents() {
        AgentEntity ours = canary("c1", "base-agent", 40);
        AgentEntity unrelated = canary("c2", "other-agent", 30);
        when(agentRepository.findByActiveTrue()).thenReturn(List.of(ours, unrelated));

        Map<String, Object> status = service.getCanaryStatus("base-agent");
        assertEquals(1, status.get("canaryCount"));
        assertEquals(40, status.get("totalCanaryPercentage"));
    }

    // ════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════

    private static AgentEntity canary(String id, String baseAgentId, Integer percentage) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(id); // mirrors DB nullable=false constraint; getCanaryStatus uses Map.of which rejects null values
        a.setActive(true);
        a.setCanaryBaseAgentId(baseAgentId);
        a.setCanaryPercentage(percentage);
        return a;
    }

    /** Run {@code body} with {@code ThreadLocalRandom.current().nextInt(100)} pinned to {@code roll}. */
    private static void withRoll(int roll, Runnable body) {
        try (MockedStatic<ThreadLocalRandom> mocked = Mockito.mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom rng = mock(ThreadLocalRandom.class);
            mocked.when(ThreadLocalRandom::current).thenReturn(rng);
            when(rng.nextInt(100)).thenReturn(roll);
            body.run();
        }
    }
}
