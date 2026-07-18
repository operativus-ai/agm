package ai.operativus.agentmanager.compute.routing;

import ai.operativus.agentmanager.core.model.RouterStepConfig;
import ai.operativus.agentmanager.core.model.enums.RouteSelectorType;
import ai.operativus.agentmanager.core.spi.RouteSelector;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HitlRouteSelectorTest {

    private final HitlRouteSelector selector = new HitlRouteSelector();

    @Test
    void selectorType_isHitl() {
        assertEquals(RouteSelectorType.HITL, selector.selectorType());
    }

    @Test
    void selectChoice_anyInput_returnsHitlPendingSentinel() {
        RouterStepConfig cfg = new RouterStepConfig(
                RouteSelectorType.HITL, null, Map.of("a", "step-a", "b", "step-b"), null);
        assertEquals(RouteSelector.HITL_PENDING, selector.selectChoice(cfg, "any prior output"));
    }

    @Test
    void selectChoice_nullPriorOutput_stillReturnsSentinel() {
        RouterStepConfig cfg = new RouterStepConfig(
                RouteSelectorType.HITL, null, Map.of("a", "step-a"), "a");
        assertEquals(RouteSelector.HITL_PENDING, selector.selectChoice(cfg, null));
    }

    @Test
    void selectChoice_nullConfig_throwsExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> selector.selectChoice(null, "{}"));
    }
}
