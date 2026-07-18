package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.core.model.MetricConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

import org.springframework.stereotype.Component;

/**
 * Domain Responsibility: Scans incoming LLM responses to detect and block the generation of harmful or restricted content.
 * State: Stateless
 */
@Component
public class ContentSafetyAdvisor implements CallAdvisor, StreamAdvisor {

    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContentSafetyAdvisor.class);
    private final ModerationService moderationService;
    /** §2 advisor-chain decomposition: per-advisor processing-time timer, tag {@code advisor=content_safety}. */
    private final Timer durationTimer;
    /** Per-scan outcome counter. {@code outcome=ok} when ModerationService returns cleanly,
     *  {@code outcome=blocked} when it throws SecurityException. Mirrors the
     *  {@code agm.security.prompt_injection.scanned} taxonomy. */
    private final Counter scannedOk;
    private final Counter scannedBlocked;
    /** Distribution of {@link ModerationResult#riskScore} for non-blocked scans. Backends that
     *  populate non-zero soft-signal scores (future regex extensions, model-based moderation)
     *  surface here without any advisor wiring change. */
    private final DistributionSummary riskScoreDistribution;

    public ContentSafetyAdvisor(ModerationService moderationService, MeterRegistry meterRegistry) {
        this.moderationService = moderationService;
        this.durationTimer = Timer.builder(MetricConstants.ADVISOR_DURATION_MS)
                .tag("advisor", "content_safety").register(meterRegistry);
        this.scannedOk = Counter.builder(MetricConstants.CONTENT_SAFETY_SCANNED)
                .tag("outcome", "ok").register(meterRegistry);
        this.scannedBlocked = Counter.builder(MetricConstants.CONTENT_SAFETY_SCANNED)
                .tag("outcome", "blocked").register(meterRegistry);
        this.riskScoreDistribution = DistributionSummary.builder(MetricConstants.CONTENT_SAFETY_RISK_SCORE)
                .description("Distribution of ModerationResult.riskScore for non-blocked content scans")
                .register(meterRegistry);
        log.info("Initialized ContentSafetyAdvisor with Active Service: {}", moderationService.getClass().getSimpleName());
    }

    @Override
    public String getName() {
        return "ContentSafetyAdvisor";
    }

    @Override
    public int getOrder() {
        return 20; // Run after PII
    }

    /**
     * @summary Intercepts synchronous chat client calls to validate output safety.
     * @logic Delegates to the next advisor in the chain and inspects the generated text response against safety policies.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> {
            ChatClientResponse response = chain.nextCall(request);

            String content = response.chatResponse().getResult().getOutput().getText();
            try {
                ModerationResult result = moderationService.checkContent(content);
                scannedOk.increment();
                riskScoreDistribution.record(result.riskScore());
            } catch (SecurityException e) {
                scannedBlocked.increment();
                throw e;
            }

            return response;
        });
    }

    /**
     * @summary Intercepts streaming chat client calls to validate output safety as a HARD GATE.
     * @logic Buffers the entire downstream Flux via {@code collectList()} before deciding to
     *        emit. Moderation runs on the joined chunks; if it passes, all chunks emit in
     *        order. If it fails, the stream errors with the original {@code SecurityException}
     *        without ever emitting the unsafe content.
     *
     *        <p>Audit F4 fix: the previous implementation accumulated chunks via
     *        {@code doOnNext} (passthrough — chunks reached the client immediately) and
     *        moderated in {@code doOnComplete}. Throwing from {@code doOnComplete} produces
     *        an {@code onError} signal AFTER all {@code onNext} emissions — the user had
     *        already received and rendered the unsafe content. ContentSafetyAdvisor's
     *        domain responsibility is "block the generation of harmful content"; that contract
     *        cannot coexist with token-by-token streaming. Buffering the full response is
     *        the correct trade — UX cost (the streaming-vs-blocking distinction collapses
     *        for moderation-blocked agents) is the price of the safety guarantee.</p>
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request)
                .collectList()
                .flatMapMany(list -> {
                    String fullText = list.stream()
                            .map(r -> {
                                if (r != null && r.chatResponse() != null
                                        && r.chatResponse().getResult() != null
                                        && r.chatResponse().getResult().getOutput() != null) {
                                    return r.chatResponse().getResult().getOutput().getText();
                                }
                                return null;
                            })
                            .filter(t -> t != null)
                            .collect(java.util.stream.Collectors.joining());

                    if (fullText.isBlank()) {
                        return Flux.fromIterable(list);
                    }

                    try {
                        ModerationResult result = moderationService.checkContent(fullText);
                        scannedOk.increment();
                        riskScoreDistribution.record(result.riskScore());
                        return Flux.fromIterable(list);
                    } catch (SecurityException e) {
                        scannedBlocked.increment();
                        return Flux.error(e);
                    }
                });
    }
}
