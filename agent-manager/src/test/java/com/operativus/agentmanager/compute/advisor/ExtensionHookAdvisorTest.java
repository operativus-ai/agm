package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import com.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import com.operativus.agentmanager.core.spi.OutputPiiScrubber;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExtensionHookAdvisor covering:
 * - Advisor metadata (name, order)
 * - No-op passthrough when no hooks are assigned
 * - Webhook dispatch invocation
 * - SPI opt-in filtering
 * - Fail-safe error handling (hooks fail but LLM call proceeds)
 */
@ExtendWith(MockitoExtension.class)
class ExtensionHookAdvisorTest {

    @Mock
    private ExtensionRegistrationRepository extensionRepository;

    @Mock
    private WebClient webClient;

    @Mock
    private CallAdvisorChain callChain;

    @Mock
    private StreamAdvisorChain streamChain;

    @Mock
    private ChatClientRequest request;

    @Mock
    private ChatClientResponse response;

    @Mock
    private Prompt prompt;

    @Nested
    @DisplayName("Advisor Metadata")
    class MetadataTests {

        @Test
        @DisplayName("getName() returns 'ExtensionHookAdvisor'")
        void testGetName() {
            ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                    Collections.emptyList(), Collections.emptyList(), extensionRepository, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());
            assertThat(advisor.getName()).isEqualTo("ExtensionHookAdvisor");
        }

        @Test
        @DisplayName("getOrder() returns 15 (runs AFTER PII redactor at order 10 — see F12)")
        void testGetOrder() {
            ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                    Collections.emptyList(), Collections.emptyList(), extensionRepository, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());
            assertThat(advisor.getOrder()).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("No-Op Passthrough (No Hooks)")
    class NoOpTests {

        @Test
        @DisplayName("adviseCall with empty hooks passes through to chain without side effects")
        void testAdviseCallNoHooks() {
            ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                    Collections.emptyList(), Collections.emptyList(), extensionRepository, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());

            when(callChain.nextCall(any())).thenReturn(response);

            ChatClientResponse result = advisor.adviseCall(request, callChain);

            assertThat(result).isSameAs(response);
            verify(callChain).nextCall(request);
            verifyNoInteractions(extensionRepository);
        }

        @Test
        @DisplayName("adviseCall with null hooks passes through to chain without side effects")
        void testAdviseCallNullHooks() {
            ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                    null, null, extensionRepository, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());

            when(callChain.nextCall(any())).thenReturn(response);

            ChatClientResponse result = advisor.adviseCall(request, callChain);

            assertThat(result).isSameAs(response);
            verify(callChain).nextCall(request);
            verifyNoInteractions(extensionRepository);
        }

        @Test
        @DisplayName("adviseStream with empty hooks passes through to chain")
        void testAdviseStreamNoHooks() {
            ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                    Collections.emptyList(), Collections.emptyList(), extensionRepository, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());

            Flux<ChatClientResponse> mockFlux = Flux.just(response);
            when(streamChain.nextStream(any())).thenReturn(mockFlux);

            Flux<ChatClientResponse> result = advisor.adviseStream(request, streamChain);

            assertThat(result).isNotNull();
            verify(streamChain).nextStream(request);
            verifyNoInteractions(extensionRepository);
        }
    }

    @Nested
    @DisplayName("Fail-Safe Error Handling")
    class FailSafeTests {

        @Test
        @DisplayName("adviseCall proceeds even when pre-hook extension is not found in repository")
        void testAdviseCallMissingExtensionProceeds() {
            ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                    List.of("non-existent-hook"), Collections.emptyList(), extensionRepository, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());

            when(request.prompt()).thenReturn(prompt);
            when(prompt.getContents()).thenReturn("test input");
            when(extensionRepository.findById("non-existent-hook")).thenReturn(Optional.empty());
            when(callChain.nextCall(any())).thenReturn(response);

            ChatClientResponse result = advisor.adviseCall(request, callChain);

            assertThat(result).isSameAs(response);
            verify(callChain).nextCall(request);
        }

        @Test
        @DisplayName("adviseCall proceeds even when pre-hook extension is inactive")
        void testAdviseCallInactiveExtensionSkipped() {
            ExtensionRegistrationEntity inactiveExt = new ExtensionRegistrationEntity();
            inactiveExt.setId("inactive-hook");
            inactiveExt.setActive(false);
            inactiveExt.setType("WEBHOOK");

            ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                    List.of("inactive-hook"), Collections.emptyList(), extensionRepository, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());

            when(request.prompt()).thenReturn(prompt);
            when(prompt.getContents()).thenReturn("test input");
            when(extensionRepository.findById("inactive-hook")).thenReturn(Optional.of(inactiveExt));
            when(callChain.nextCall(any())).thenReturn(response);

            ChatClientResponse result = advisor.adviseCall(request, callChain);

            assertThat(result).isSameAs(response);
            verify(callChain).nextCall(request);
        }

        @Test
        @DisplayName("adviseCall proceeds even when webhook POST throws an exception")
        void testAdviseCallWebhookFailureProceeds() {
            ExtensionRegistrationEntity webhookExt = new ExtensionRegistrationEntity();
            webhookExt.setId("failing-webhook");
            webhookExt.setActive(true);
            webhookExt.setType("WEBHOOK");
            webhookExt.setUrl("http://unreachable-host:9999/hook");

            ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                    List.of("failing-webhook"), Collections.emptyList(), extensionRepository, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());

            when(request.prompt()).thenReturn(prompt);
            when(prompt.getContents()).thenReturn("test input");
            when(extensionRepository.findById("failing-webhook")).thenReturn(Optional.of(webhookExt));

            // WebClient mock that throws on POST
            WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
            when(webClient.post()).thenReturn(uriSpec);
            when(uriSpec.uri(anyString())).thenThrow(new RuntimeException("Connection refused"));

            when(callChain.nextCall(any())).thenReturn(response);

            // Should not throw — fail-safe behavior
            ChatClientResponse result = advisor.adviseCall(request, callChain);

            assertThat(result).isSameAs(response);
            verify(callChain).nextCall(request);
        }
    }

    @Nested
    @DisplayName("MCP Extension Skipping")
    class McpSkipTests {

        @Test
        @DisplayName("MCP type extensions are skipped in hook advisor (handled by McpConnectionPool)")
        void testMcpExtensionSkipped() {
            ExtensionRegistrationEntity mcpExt = new ExtensionRegistrationEntity();
            mcpExt.setId("mcp-server-1");
            mcpExt.setActive(true);
            mcpExt.setType("MCP");
            mcpExt.setUrl("http://mcp-server:8080/mcp");

            ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                    List.of("mcp-server-1"), Collections.emptyList(), extensionRepository, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());

            when(request.prompt()).thenReturn(prompt);
            when(prompt.getContents()).thenReturn("test input");
            when(extensionRepository.findById("mcp-server-1")).thenReturn(Optional.of(mcpExt));
            when(callChain.nextCall(any())).thenReturn(response);

            ChatClientResponse result = advisor.adviseCall(request, callChain);

            assertThat(result).isSameAs(response);
            // WebClient should NOT have been called for MCP type
            verify(webClient, never()).post();
        }
    }

    @Nested
    @DisplayName("Post-Hook Execution")
    class PostHookTests {

        @Test
        @DisplayName("adviseCall executes post-hooks after LLM response")
        void testPostHookExecution() {
            ExtensionRegistrationEntity webhookExt = new ExtensionRegistrationEntity();
            webhookExt.setId("post-telemetry");
            webhookExt.setActive(true);
            webhookExt.setType("WEBHOOK");
            webhookExt.setUrl("http://telemetry:8080/hook");

            ExtensionHookAdvisor advisor = new ExtensionHookAdvisor(
                    Collections.emptyList(), List.of("post-telemetry"), extensionRepository, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());

            // Mock the LLM response chain
            ChatResponse chatResp = mock(ChatResponse.class);
            Generation gen = mock(Generation.class);
            AssistantMessage assistantMsg = mock(AssistantMessage.class);

            when(callChain.nextCall(any())).thenReturn(response);
            when(response.chatResponse()).thenReturn(chatResp);
            when(chatResp.getResult()).thenReturn(gen);
            when(gen.getOutput()).thenReturn(assistantMsg);
            when(assistantMsg.getText()).thenReturn("LLM output");

            when(extensionRepository.findById("post-telemetry")).thenReturn(Optional.of(webhookExt));

            // WebClient chain mock
            WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
            WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
            WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
            WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

            when(webClient.post()).thenReturn(uriSpec);
            when(uriSpec.uri(anyString())).thenReturn(bodySpec);
            when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
            when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(String.class)).thenReturn(reactor.core.publisher.Mono.just("ok"));

            ChatClientResponse result = advisor.adviseCall(request, callChain);

            assertThat(result).isSameAs(response);
            verify(extensionRepository).findById("post-telemetry");
            verify(webClient).post();
        }
    }
}
