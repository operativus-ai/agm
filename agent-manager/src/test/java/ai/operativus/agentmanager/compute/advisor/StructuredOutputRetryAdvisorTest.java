package ai.operativus.agentmanager.compute.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StructuredOutputRetryAdvisor}. Verifies:
 * - Valid JSON passes through without retry attempts
 * - Invalid JSON triggers the reflection loop with correct retry count
 * - Successful retry on second attempt short-circuits remaining retries
 * - Max retries exhaustion returns last response (degraded but non-fatal)
 * - Empty/null responses pass through without validation
 */
class StructuredOutputRetryAdvisorTest {

    private StructuredOutputRetryAdvisor advisor;
    private CallAdvisorChain chain;
    private ChatClientRequest request;

    @BeforeEach
    void setUp() {
        advisor = new StructuredOutputRetryAdvisor(3, new SimpleMeterRegistry());
        chain = mock(CallAdvisorChain.class);
        request = mock(ChatClientRequest.class);

        // Default: request always returns a mutable builder
        ChatClientRequest.Builder mutateBuilder = mock(ChatClientRequest.Builder.class);
        when(request.mutate()).thenReturn(mutateBuilder);
        when(mutateBuilder.prompt(any(Prompt.class))).thenReturn(mutateBuilder);
        when(mutateBuilder.build()).thenReturn(request);

        Prompt prompt = mock(Prompt.class);
        when(prompt.getInstructions()).thenReturn(List.of(new UserMessage("test")));
        when(prompt.getOptions()).thenReturn(null);
        when(request.prompt()).thenReturn(prompt);
    }

    @Test
    @DisplayName("getName returns StructuredOutputRetryAdvisor")
    void testGetName() {
        assertThat(advisor.getName()).isEqualTo("StructuredOutputRetryAdvisor");
    }

    @Test
    @DisplayName("getOrder returns 1 (early advisor)")
    void testGetOrder() {
        assertThat(advisor.getOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("Valid JSON passes through without any retry attempts")
    void testValidJsonPassThrough() {
        ChatClientResponse response = mockResponseWithContent("{\"result\": \"success\", \"score\": 42}");
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isEqualTo(response);
        verify(chain, times(1)).nextCall(any()); // Only the initial call, no retries
    }

    @Test
    @DisplayName("Valid JSON array passes through without retry")
    void testValidJsonArrayPassThrough() {
        ChatClientResponse response = mockResponseWithContent("[{\"id\": 1}, {\"id\": 2}]");
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isEqualTo(response);
        verify(chain, times(1)).nextCall(any());
    }

    @Test
    @DisplayName("Invalid JSON triggers reflection loop and succeeds on second attempt")
    void testRetrySucceedsOnSecondAttempt() {
        ChatClientResponse badResponse = mockResponseWithContent("Here is the result: {invalid json}");
        ChatClientResponse goodResponse = mockResponseWithContent("{\"corrected\": true}");
        when(chain.nextCall(any()))
                .thenReturn(badResponse)   // Initial call fails
                .thenReturn(goodResponse); // First retry succeeds

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(extractContent(result)).isEqualTo("{\"corrected\": true}");
        verify(chain, times(2)).nextCall(any()); // Initial + 1 retry
    }

    @Test
    @DisplayName("Max retries exhaust and return last response without throwing")
    void testMaxRetriesExhaust() {
        ChatClientResponse badResponse = mockResponseWithContent("This is not JSON at all.");
        when(chain.nextCall(any())).thenReturn(badResponse); // Always returns bad JSON

        ChatClientResponse result = advisor.adviseCall(request, chain);

        // Should not throw — returns last response degraded
        assertThat(result).isNotNull();
        verify(chain, times(4)).nextCall(any()); // Initial + 3 retries
    }

    @Test
    @DisplayName("Empty content passes through without validation attempts")
    void testEmptyContentPassThrough() {
        ChatClientResponse response = mockResponseWithContent("");
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isEqualTo(response);
        verify(chain, times(1)).nextCall(any());
    }

    @Test
    @DisplayName("Null response passes through without NullPointerException")
    void testNullResponsePassThrough() {
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(null);
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isEqualTo(response);
        verify(chain, times(1)).nextCall(any());
    }

    @Test
    @DisplayName("JSON wrapped in markdown code fences is correctly validated")
    void testMarkdownFencedJsonPassThrough() {
        ChatClientResponse response = mockResponseWithContent("```json\n{\"valid\": true}\n```");
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isEqualTo(response);
        verify(chain, times(1)).nextCall(any());
    }

    @Test
    @DisplayName("Custom maxRetries=1 limits retry attempts correctly")
    void testCustomMaxRetries() {
        StructuredOutputRetryAdvisor singleRetry = new StructuredOutputRetryAdvisor(1, new SimpleMeterRegistry());
        ChatClientResponse badResponse = mockResponseWithContent("not json");
        when(chain.nextCall(any())).thenReturn(badResponse);

        singleRetry.adviseCall(request, chain);

        verify(chain, times(2)).nextCall(any()); // Initial + 1 retry only
    }

    // --- Helper Methods ---

    private ChatClientResponse mockResponseWithContent(String content) {
        AssistantMessage assistantMessage = new AssistantMessage(content);
        Generation generation = new Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));

        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        return response;
    }

    private String extractContent(ChatClientResponse response) {
        return response.chatResponse().getResult().getOutput().getText();
    }
}
