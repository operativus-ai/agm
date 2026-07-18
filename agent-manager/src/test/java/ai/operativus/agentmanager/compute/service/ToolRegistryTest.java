package ai.operativus.agentmanager.compute.service;

import ai.operativus.agentmanager.control.security.AgentContextHolder;
import ai.operativus.agentmanager.core.model.TeamManifest.AgentManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.util.Collections;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class ToolRegistryTest {

    @Mock
    private ApplicationContext applicationContext;

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry(applicationContext);
    }

    @Test
    void testGetUnregisteredTool_ReturnsNull() {
        Function<?, ?> retrievedTool = toolRegistry.getTool("missingTool");
        assertNull(retrievedTool);
    }

    @Test
    void testGetTool_ExecutionWithoutContext_ThrowsException() {
        Function<String, String> dummyTool = arg -> "Output: " + arg;
        toolRegistry.register("testTool", dummyTool);

        Function<Object, Object> retrievedTool = (Function<Object, Object>) toolRegistry.getTool("testTool");
        assertNotNull(retrievedTool);

        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> {
            retrievedTool.apply("hello");
        });
    }

    @Test
    void testGetTool_ExecutionWithContext() throws Exception {
        Function<Integer, Integer> mathTool = val -> val * 2;
        toolRegistry.register("mathTool", mathTool);

        Function<Object, Object> retrievedTool = (Function<Object, Object>) toolRegistry.getTool("mathTool");
        assertNotNull(retrievedTool);

        AgentManifest manifest = new AgentManifest("agent1", "Agent One", Collections.emptyList(), false);
        AgentContextHolder.AgentContext context = new AgentContextHolder.AgentContext(
                "team-1", "user-1", 10.0, manifest
        );

        Object result = java.lang.ScopedValue.where(AgentContextHolder.CONTEXT, context)
            .call(() -> retrievedTool.apply(10));

        assertEquals(20, result);
    }
}
