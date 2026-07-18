package ai.operativus.agentmanager.compute.tools;

import ai.operativus.agentmanager.core.registry.MemoryOperations;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Domain Responsibility: Runtime test for {@link ActiveMemoryTools#saveMemoryTool} —
 * verifies the parity §2.11 Mem0 ✅ claim from agm-agno-tool-parity-analysis.md.
 * Asserts the 3 vectors per docs/plans/agm-tools-impl.md §3:
 *   (a) success returns "Memory saved successfully." canonical string and persists fact
 *   (b) missing/null userId still resolves via SecurityContextUtils default — function does not throw
 *   (c) memoryService throws → graceful "Error saving memory:" prefix is returned (no rethrow)
 *
 * State: Stateless. Mockito stub of MemoryOperations is independent ground truth (A18).
 * The tool wraps memoryService.addMemory in a try/catch that returns a graceful error string.
 */
class ActiveMemoryToolsTest {

    private final MemoryOperations memoryService = mock(MemoryOperations.class);
    private final Function<ActiveMemoryTools.SaveMemoryRequest, String> tool =
            new ActiveMemoryTools().saveMemoryTool(memoryService);

    // (a) success path
    @Test
    void saveMemory_success_returnsCanonicalString() {
        ActiveMemoryTools.SaveMemoryRequest request =
                new ActiveMemoryTools.SaveMemoryRequest("user prefers dark mode", "user-1");

        String result = tool.apply(request);

        assertEquals("Memory saved successfully.", result);
        verify(memoryService).addMemory("user prefers dark mode");
    }

    // (b) missing userId — null handled, function does not throw, fact still persisted
    @Test
    void saveMemory_nullUserId_stillResolvesAndSucceeds() {
        ActiveMemoryTools.SaveMemoryRequest request =
                new ActiveMemoryTools.SaveMemoryRequest("user said hello", null);

        String result = tool.apply(request);

        assertEquals("Memory saved successfully.", result);
        verify(memoryService).addMemory("user said hello");
    }

    // (c) memoryService throws → graceful error string
    @Test
    void saveMemory_memoryServiceThrows_returnsErrorString() {
        doThrow(new RuntimeException("vector store unavailable"))
                .when(memoryService).addMemory("fact");
        ActiveMemoryTools.SaveMemoryRequest request =
                new ActiveMemoryTools.SaveMemoryRequest("fact", "user-1");

        String result = tool.apply(request);

        assertTrue(result.startsWith("Error saving memory:"),
                "expected error prefix, got: " + result);
        assertTrue(result.contains("vector store unavailable"),
                "expected cause echoed, got: " + result);
    }
}
