package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.compute.security.DeterministicNEREngine;
import ai.operativus.agentmanager.compute.security.FormatPreservingEncryptionService;
import ai.operativus.agentmanager.compute.security.PiiAuditLogEntity;
import ai.operativus.agentmanager.compute.security.PiiAuditLogRepository;
import ai.operativus.agentmanager.compute.security.PiiPolicyEntity;
import ai.operativus.agentmanager.compute.security.PiiPolicyService;
import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@link PIIAnonymizationAdvisor} substitution semantics. This is a security-class
 * regression guard: if any branch of the redaction chain silently passes original PII
 * through (scrub no-ops, audit double-counts, output-guard rebuild loses text), prod
 * agents would leak user PII into LLM provider payloads and into the audit trail.
 *
 * <p>{@code AdvisorPiiBoundaryContractTest} already pins the chain-order boundary at 10.
 * This complements it by pinning the per-call substitution + audit semantics.
 *
 * <p>Per-tenant policy resolution (changeset 101): the advisor reads
 * {@code AgentContextHolder.getOrgId()} to scope policy lookups. Tests bind orgId via
 * {@code SecurityContextHolder} → {@code UserDetailsImpl} fallback (PR #927) so the
 * holder resolves a tenant in @BeforeEach.
 *
 * <p>Cases:
 * <ol>
 *   <li>Request-side: scrubbed text substituted into UserMessage; events audited; counters incremented.</li>
 *   <li>Empty policy list: request unchanged; {@code outcome=clean} counter; no audit.</li>
 *   <li>Policies present but zero events: request unchanged; {@code outcome=clean}; no audit; no event count.</li>
 *   <li>No agentId OR no orgId in scope: passthrough — no policy lookup, no scrub.</li>
 *   <li>Response-side: output AssistantMessage rebuilt with scrubbed text + {@code _OUTPUT_GUARD} audit suffix.</li>
 *   <li>Audit persistence failure: logged and swallowed; request still flows to chain.</li>
 *   <li>Streaming: request redacted before {@code chain.nextStream}; response stream untouched (audit F5 documented gap).</li>
 *   <li>{@code OutputPiiScrubber#scrub}: applies policies without auditing (audit responsibility belongs to {@code redactResponse}).</li>
 *   <li>Order/name pinned at 10 / "PIIAnonymizationAdvisor".</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PIIAnonymizationAdvisorTest {

    private static final String TEST_ORG = "test-org";

    @Mock private PiiPolicyService policyService;
    @Mock private DeterministicNEREngine nerEngine;
    @Mock private FormatPreservingEncryptionService fpeService;
    @Mock private PiiAuditLogRepository auditLogRepository;
    @Mock private CallAdvisorChain chain;
    @Mock private StreamAdvisorChain streamChain;
    @Mock private PiiPolicyEntity policy;

    private SimpleMeterRegistry meterRegistry;
    private PIIAnonymizationAdvisor advisor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        advisor = new PIIAnonymizationAdvisor(policyService, nerEngine, fpeService, auditLogRepository, meterRegistry);
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
    void requestSide_substitutesScrubbedText_andAuditsEvents() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("agentId", "agent-1");
        ctx.put("sessionId", "session-7");
        ChatClientRequest in = request("My SSN is 123-45-6789", ctx);

        when(policyService.findPoliciesForAgent("agent-1", TEST_ORG)).thenReturn(List.of(policy));
        DeterministicNEREngine.ScrubResult scrub = new DeterministicNEREngine.ScrubResult(
                "My SSN is [REDACTED]",
                List.of(
                        new DeterministicNEREngine.ScrubEvent("US_SSN", "REPLACE"),
                        new DeterministicNEREngine.ScrubEvent("PII_HASH", "MASK")));
        when(nerEngine.scrub("My SSN is 123-45-6789", List.of(policy), fpeService)).thenReturn(scrub);
        when(chain.nextCall(any())).thenReturn(response(""));

        advisor.adviseCall(in, chain);

        ArgumentCaptor<ChatClientRequest> reqCaptor = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(reqCaptor.capture());
        assertThat(reqCaptor.getValue().prompt().getContents())
                .as("LLM must receive the SCRUBBED text, never the original PII")
                .isEqualTo("My SSN is [REDACTED]");

        ArgumentCaptor<List<PiiAuditLogEntity>> auditCaptor = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).saveAll(auditCaptor.capture());
        List<PiiAuditLogEntity> audited = auditCaptor.getValue();
        assertThat(audited).hasSize(2);
        assertThat(audited.get(0).getAgentId()).isEqualTo("agent-1");
        assertThat(audited.get(0).getSessionId()).isEqualTo("session-7");
        assertThat(audited).extracting(PiiAuditLogEntity::getPolicyName)
                .containsExactly("US_SSN", "PII_HASH");

        Counter redacted = meterRegistry.find("agm.security.pii.scanned").tag("outcome", "redacted").counter();
        Counter clean = meterRegistry.find("agm.security.pii.scanned").tag("outcome", "clean").counter();
        Counter events = meterRegistry.find("agm.security.pii.redaction_events").counter();
        assertThat(redacted).isNotNull();
        assertThat(redacted.count()).isEqualTo(1.0d);
        assertThat(clean.count()).isEqualTo(0.0d);
        assertThat(events.count()).as("event counter must reflect distinct redaction count").isEqualTo(2.0d);
    }

    @Test
    void emptyPolicyList_passesRequestUnchanged_emitsCleanCounter_noAudit() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("agentId", "agent-1");
        ChatClientRequest in = request("anything", ctx);

        when(policyService.findPoliciesForAgent("agent-1", TEST_ORG)).thenReturn(List.of());
        when(chain.nextCall(in)).thenReturn(response(""));

        advisor.adviseCall(in, chain);

        verify(chain).nextCall(in);
        verify(nerEngine, never()).scrub(any(), any(), any());
        verify(auditLogRepository, never()).saveAll(any());
        Counter clean = meterRegistry.find("agm.security.pii.scanned").tag("outcome", "clean").counter();
        assertThat(clean.count()).isEqualTo(1.0d);
    }

    @Test
    void policiesPresentButZeroEvents_emitsCleanCounter_noAudit() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("agentId", "agent-1");
        ChatClientRequest in = request("clean prompt", ctx);

        when(policyService.findPoliciesForAgent("agent-1", TEST_ORG)).thenReturn(List.of(policy));
        when(nerEngine.scrub("clean prompt", List.of(policy), fpeService))
                .thenReturn(new DeterministicNEREngine.ScrubResult("clean prompt", List.of()));
        when(chain.nextCall(in)).thenReturn(response(""));

        advisor.adviseCall(in, chain);

        verify(auditLogRepository, never()).saveAll(any());
        Counter clean = meterRegistry.find("agm.security.pii.scanned").tag("outcome", "clean").counter();
        Counter events = meterRegistry.find("agm.security.pii.redaction_events").counter();
        assertThat(clean.count()).isEqualTo(1.0d);
        assertThat(events.count()).isEqualTo(0.0d);
    }

    @Test
    void noAgentIdInContext_fallsBackToTenantWidePolicyList() {
        // Post-changeset-101: when agentId is missing but a tenant context IS bound, the
        // advisor still scopes by orgId and uses the tenant's enabled policies as the
        // fallback (PiiPolicyService.findPoliciesForAgent falls through to
        // findByOrgIdAndEnabledTrue when no per-agent bindings exist). This preserves the
        // pre-#979 behavior where requests on system paths still had PII filtering, just
        // with per-tenant scoping instead of a global dictionary.
        ChatClientRequest in = request("anything", new HashMap<>());
        when(policyService.findPoliciesForAgent(eq(null), eq(TEST_ORG))).thenReturn(List.of());
        when(chain.nextCall(in)).thenReturn(response(""));

        advisor.adviseCall(in, chain);

        verify(policyService).findPoliciesForAgent(eq(null), eq(TEST_ORG));
        verify(chain).nextCall(in);
    }

    @Test
    void noOrgIdResolvable_skipsPolicyLookup_passesThrough() {
        // Even when agentId is present, if no tenant context can be resolved
        // (no ScopedValue, no SecurityContext) the advisor bypasses — refuses to guess
        // which tenant's policies apply.
        SecurityContextHolder.clearContext();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("agentId", "agent-1");
        ChatClientRequest in = request("My SSN is 123-45-6789", ctx);
        when(chain.nextCall(in)).thenReturn(response(""));

        advisor.adviseCall(in, chain);

        verify(policyService, never()).findPoliciesForAgent(any(), any());
        verify(nerEngine, never()).scrub(any(), any(), any());
        verify(chain).nextCall(in);
    }

    @Test
    void responseSide_rebuildsAssistantMessageWithScrubbedText_andAuditsAsOutputGuard() {
        // Use a blank request prompt to skip request-side scrub entirely, isolating
        // the response-side rebuild path.
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("agentId", "agent-1");
        ctx.put("sessionId", "sess-9");
        ChatClientRequest in = request("", ctx);

        when(policyService.findPoliciesForAgent("agent-1", TEST_ORG)).thenReturn(List.of(policy));
        when(chain.nextCall(in)).thenReturn(response("Reply contains 123-45-6789"));
        when(nerEngine.scrub("Reply contains 123-45-6789", List.of(policy), fpeService))
                .thenReturn(new DeterministicNEREngine.ScrubResult(
                        "Reply contains [REDACTED]",
                        List.of(new DeterministicNEREngine.ScrubEvent("US_SSN", "REPLACE"))));

        ChatClientResponse out = advisor.adviseCall(in, chain);

        assertThat(out.chatResponse().getResult().getOutput().getText())
                .as("Output guard must REWRITE the assistant message; reflective-write regression (audit F9) must not regress")
                .isEqualTo("Reply contains [REDACTED]");

        ArgumentCaptor<List<PiiAuditLogEntity>> auditCaptor = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).saveAll(auditCaptor.capture());
        assertThat(auditCaptor.getValue()).hasSize(1);
        assertThat(auditCaptor.getValue().get(0).getPolicyName())
                .as("Output-side audit row must carry _OUTPUT_GUARD suffix to distinguish from request-side rows")
                .isEqualTo("US_SSN_OUTPUT_GUARD");
    }

    @Test
    void auditPersistenceFailure_isSwallowed_chainStillReturns() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("agentId", "agent-1");
        ChatClientRequest in = request("My SSN is 123", ctx);

        when(policyService.findPoliciesForAgent("agent-1", TEST_ORG)).thenReturn(List.of(policy));
        when(nerEngine.scrub("My SSN is 123", List.of(policy), fpeService))
                .thenReturn(new DeterministicNEREngine.ScrubResult(
                        "My SSN is [REDACTED]",
                        List.of(new DeterministicNEREngine.ScrubEvent("US_SSN", "REPLACE"))));
        when(auditLogRepository.saveAll(any())).thenThrow(new RuntimeException("DB down"));
        when(chain.nextCall(any())).thenReturn(response(""));

        // No throw — audit failure must not break the request path.
        assertThat(advisor.adviseCall(in, chain)).isNotNull();
        verify(chain).nextCall(any());
    }

    @Test
    void streamingPath_redactsRequestOnly_responseStreamUntouched() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("agentId", "agent-1");
        ChatClientRequest in = request("PII text", ctx);

        when(policyService.findPoliciesForAgent("agent-1", TEST_ORG)).thenReturn(List.of(policy));
        when(nerEngine.scrub("PII text", List.of(policy), fpeService))
                .thenReturn(new DeterministicNEREngine.ScrubResult(
                        "[REDACTED]",
                        List.of(new DeterministicNEREngine.ScrubEvent("X", "REPLACE"))));
        when(streamChain.nextStream(any())).thenReturn(Flux.empty());

        advisor.adviseStream(in, streamChain).blockLast();

        ArgumentCaptor<ChatClientRequest> reqCaptor = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(streamChain).nextStream(reqCaptor.capture());
        assertThat(reqCaptor.getValue().prompt().getContents()).isEqualTo("[REDACTED]");
    }

    @Test
    void outputPiiScrubber_scrubAppliesPolicies_butDoesNotAudit() {
        // OutputPiiScrubber.scrub is called by ExtensionHookAdvisor (order 15) BEFORE
        // the order-10 PII guard runs on the response. To avoid double-counting in
        // pii_audit_log, this entry point must scrub WITHOUT emitting an audit row.
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("agentId", "agent-1");
        ChatClientRequest req = request("", ctx);
        when(policyService.findPoliciesForAgent("agent-1", TEST_ORG)).thenReturn(List.of(policy));
        when(nerEngine.scrub("hook payload PII", List.of(policy), fpeService))
                .thenReturn(new DeterministicNEREngine.ScrubResult(
                        "hook payload [REDACTED]",
                        List.of(new DeterministicNEREngine.ScrubEvent("X", "REPLACE"))));

        String scrubbed = advisor.scrub("hook payload PII", req);

        assertThat(scrubbed).isEqualTo("hook payload [REDACTED]");
        verify(auditLogRepository, never()).saveAll(any());
    }

    @Test
    void orderingAndName_arePinnedAtPiiBoundary() {
        // Order 10 is THE PII BOUNDARY. Any advisor that reads request.prompt().getContents()
        // must run at order >= 10; AdvisorPiiBoundaryContractTest enforces the boundary,
        // but a silent change to this advisor's order would re-introduce audit F11/F12.
        assertThat(advisor.getOrder()).isEqualTo(10);
        assertThat(advisor.getName()).isEqualTo("PIIAnonymizationAdvisor");
    }

    private static ChatClientRequest request(String text, Map<String, Object> ctx) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(text)))
                .context(ctx)
                .build();
    }

    private static ChatClientResponse response(String assistantText) {
        AssistantMessage msg = new AssistantMessage(assistantText);
        Generation gen = new Generation(msg);
        ChatResponse chat = new ChatResponse(List.of(gen));
        return ChatClientResponse.builder().chatResponse(chat).build();
    }
}
