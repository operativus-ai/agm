package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.compute.security.DeterministicNEREngine;
import ai.operativus.agentmanager.compute.security.FormatPreservingEncryptionService;
import ai.operativus.agentmanager.compute.security.PiiAuditLogEntity;
import ai.operativus.agentmanager.compute.security.PiiAuditLogRepository;
import ai.operativus.agentmanager.compute.security.PiiPolicyEntity;
import ai.operativus.agentmanager.compute.security.PiiPolicyService;
import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.entity.ComplianceTier;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class StatefulStreamingPIIAdvisorTest {

    @Mock
    private PiiPolicyService policyService;
    @Mock
    private DeterministicNEREngine nerEngine;
    @Mock
    private FormatPreservingEncryptionService fpeService;
    @Mock
    private PiiAuditLogRepository auditLogRepository;
    @Mock
    private AgentRepository agentRepository;

    @Mock
    private StreamAdvisorChain chain;
    
    @Mock
    private ChatClientRequest request;

    private StatefulStreamingPIIAdvisor advisor;

    private static final String TEST_ORG = "test-org";

    @BeforeEach
    void setUp() {
        advisor = new StatefulStreamingPIIAdvisor(policyService, nerEngine, fpeService, auditLogRepository, agentRepository);
        UserDetailsImpl ud = new UserDetailsImpl(UUID.randomUUID(), "test-user",
                "test@test.local", TEST_ORG, false, "pwd", List.of());
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(ud, null, List.of())));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adviseStream_NoAgentId_ReturnsOriginalStream() {
        when(request.context()).thenReturn(Map.of());
        Flux<ChatClientResponse> originalFlux = Flux.empty();
        when(chain.nextStream(request)).thenReturn(originalFlux);

        Flux<ChatClientResponse> result = advisor.adviseStream(request, chain);

        assertSame(originalFlux, result);
        verify(agentRepository, never()).findById(anyString());
    }

    @Test
    void adviseStream_Tier1Agent_ReturnsOriginalStream() {
        when(request.context()).thenReturn(Map.of(PIIAnonymizationAdvisor.AGENT_ID_KEY, "agent-123"));
        AgentEntity agent = new AgentEntity();
        agent.setComplianceTier(ComplianceTier.TIER_1_STANDARD);
        when(agentRepository.findById("agent-123")).thenReturn(Optional.of(agent));
        
        Flux<ChatClientResponse> originalFlux = Flux.empty();
        when(chain.nextStream(request)).thenReturn(originalFlux);

        Flux<ChatClientResponse> result = advisor.adviseStream(request, chain);

        assertSame(originalFlux, result);
        verify(policyService, never()).findPoliciesForAgent(anyString(), anyString());
    }

    @Test
    void adviseStream_Tier2Strict_NoPolicies_ReturnsOriginalStream() {
        when(request.context()).thenReturn(Map.of(PIIAnonymizationAdvisor.AGENT_ID_KEY, "agent-123"));
        AgentEntity agent = new AgentEntity();
        agent.setComplianceTier(ComplianceTier.TIER_2_STRICT);
        agent.setOrgId(TEST_ORG);
        when(agentRepository.findById("agent-123")).thenReturn(Optional.of(agent));
        when(policyService.findPoliciesForAgent("agent-123", TEST_ORG)).thenReturn(List.of());

        Flux<ChatClientResponse> originalFlux = Flux.empty();
        when(chain.nextStream(request)).thenReturn(originalFlux);

        Flux<ChatClientResponse> result = advisor.adviseStream(request, chain);

        assertSame(originalFlux, result);
    }

    @Test
    void adviseStream_Tier2Strict_WithPolicies_ChunksAndScrubs() {
        when(request.context()).thenReturn(Map.of(PIIAnonymizationAdvisor.AGENT_ID_KEY, "agent-123"));
        AgentEntity agent = new AgentEntity();
        agent.setComplianceTier(ComplianceTier.TIER_2_STRICT);
        agent.setOrgId(TEST_ORG);
        when(agentRepository.findById("agent-123")).thenReturn(Optional.of(agent));
        
        PiiPolicyEntity policy = new PiiPolicyEntity();
        when(policyService.findPoliciesForAgent("agent-123", TEST_ORG)).thenReturn(List.of(policy));

        // Create 3 responses to simulate streaming
        ChatClientResponse mockResp1 = mock(ChatClientResponse.class);
        ChatResponse chatResp1 = new ChatResponse(List.of(new Generation(new AssistantMessage("Hello")))); // no space
        lenient().when(mockResp1.chatResponse()).thenReturn(chatResp1);

        ChatClientResponse mockResp2 = mock(ChatClientResponse.class);
        ChatResponse chatResp2 = new ChatResponse(List.of(new Generation(new AssistantMessage("World!")))); // has punctuation
        lenient().when(mockResp2.chatResponse()).thenReturn(chatResp2);

        ChatClientResponse mockResp3 = mock(ChatClientResponse.class);
        ChatResponse chatResp3 = new ChatResponse(List.of(new Generation(new AssistantMessage(" Last."))));
        lenient().when(mockResp3.chatResponse()).thenReturn(chatResp3);

        Flux<ChatClientResponse> stream = Flux.just(mockResp1, mockResp2, mockResp3);
        when(chain.nextStream(request)).thenReturn(stream);

        // Mock NER output for the first chunk ("HelloWorld!")
        DeterministicNEREngine.ScrubEvent event = new DeterministicNEREngine.ScrubEvent("EMAIL", "MASK");
        DeterministicNEREngine.ScrubResult result1 = new DeterministicNEREngine.ScrubResult("HelloX!", List.of(event));
        when(nerEngine.scrub(eq("HelloWorld!"), anyList(), any())).thenReturn(result1);

        // Mock NER output for the second chunk (" Last.")
        DeterministicNEREngine.ScrubResult result2 = new DeterministicNEREngine.ScrubResult(" Last.", List.of());
        when(nerEngine.scrub(eq(" Last."), anyList(), any())).thenReturn(result2);

        Flux<ChatClientResponse> fluxResult = advisor.adviseStream(request, chain);
        List<ChatClientResponse> collected = fluxResult.collectList().block();

        assertNotNull(collected);
        assertEquals(2, collected.size()); // It should process in 2 chunks ("HelloWorld!" and " Last.")

        // Verify the scrubbed text injection via reflection
        assertEquals("HelloX!", collected.get(0).chatResponse().getResult().getOutput().getText());
        assertEquals(" Last.", collected.get(1).chatResponse().getResult().getOutput().getText());

        // Verify Audit Log was emitted for the hit
        verify(auditLogRepository, times(1)).save(any(PiiAuditLogEntity.class));
    }
}
