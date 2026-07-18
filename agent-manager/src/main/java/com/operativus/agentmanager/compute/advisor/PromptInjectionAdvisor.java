package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.core.model.MetricConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.regex.Pattern;
/**
 * Domain Responsibility: Intercepts outgoing LLM requests to preemptively detect and block malicious prompt injection attempts.
 * State: Stateless
 */
@Component
public class PromptInjectionAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Pattern INJECTION_PATTERN = Pattern.compile(
        "(ignore\\s+all\\s+instructions|system\\s+override|delete\\s+database)",
        Pattern.CASE_INSENSITIVE
    );

    /** Counts requests scanned for prompt injection. {@code outcome=ok} when the prompt is clean,
     *  {@code outcome=blocked} when a known injection signature was detected and the request rejected. */
    private final Counter scannedOk;
    private final Counter scannedBlocked;
    /** Per-advisor processing-time timer — supports the §2 advisor-chain decomposition gap.
     *  Tag {@code advisor=prompt_injection} so a single timer name {@code advisor.duration_ms}
     *  carries all per-advisor latencies and Grafana can pivot by tag. */
    private final Timer durationTimer;

    public PromptInjectionAdvisor(MeterRegistry meterRegistry) {
        this.scannedOk = Counter.builder(MetricConstants.PROMPT_INJECTION_SCANNED)
                .tag("outcome", "ok").register(meterRegistry);
        this.scannedBlocked = Counter.builder(MetricConstants.PROMPT_INJECTION_SCANNED)
                .tag("outcome", "blocked").register(meterRegistry);
        this.durationTimer = Timer.builder(MetricConstants.ADVISOR_DURATION_MS)
                .tag("advisor", "prompt_injection").register(meterRegistry);
    }

    @Override
    public String getName() {
        return "PromptInjectionAdvisor";
    }

    @Override
    public int getOrder() {
        return 0; // High priority, run first
    }

    /**
     * @summary Intercepts synchronous chat client calls to validate input against injection attacks.
     * @logic Scans the user prompt using the internal injection detection logic and delegates to the next advisor in the chain if validation passes.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> {
            checkInjection(request.prompt().getContents());
            return chain.nextCall(request);
        });
    }

    /**
     * @summary Intercepts streaming chat client calls to validate input against injection attacks.
     * @logic Scans the user prompt using the internal injection detection logic, delegating to the next reactive advisor chain if validation passes.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        checkInjection(request.prompt().getContents());
        return chain.nextStream(request);
    }

    /**
     * @summary Validates the user text against known prompt injection signatures.
     * @logic Evaluates prompt contents against a regex pattern of restricted phrases, throwing a SecurityException if an injection signature is detected.
     */
    private void checkInjection(String userText) {
        if (userText != null && INJECTION_PATTERN.matcher(userText).find()) {
            scannedBlocked.increment();
            // Do not include userText: this advisor runs at order 0, before PIIAnonymizationAdvisor (order 10),
            // so userText may contain unredacted PII. Operators correlate via timestamp + scannedBlocked counter.
            throw new SecurityException("Potential prompt injection detected (content redacted)");
        }
        scannedOk.increment();
    }
}
