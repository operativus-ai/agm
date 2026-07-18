package com.operativus.agentmanager.core.spi;

import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Domain Responsibility: Functional handle for scrubbing PII from an LLM output text
 * BEFORE it leaves the system via a side channel (e.g. an extension webhook). The
 * production implementation is {@code PIIAnonymizationAdvisor.scrubOutputForRequest},
 * which reuses the same policies + NER engine + FPE service the response-side guardrail
 * uses — but returns the scrubbed string instead of mutating the response.
 *
 * <p>Necessary because {@code ExtensionHookAdvisor} sits at order 15, INSIDE
 * {@code PIIAnonymizationAdvisor}'s call at order 10. On the response-unwind, the post-hook
 * dispatch fires BEFORE PII's response-side scrub runs. Without an explicit scrub at the
 * post-hook seam, webhooks receive raw LLM output — closing audit F5's "post-hooks leak
 * PII" gap.</p>
 *
 * State: Stateless functional interface. {@link #NO_OP} returns text unchanged and is
 * the right choice for tests that don't exercise PII redaction.
 */
@FunctionalInterface
public interface OutputPiiScrubber {

    /**
     * @summary Returns the scrubbed form of {@code text} using whatever policies apply to the
     *          agent identified by {@code request.context()}.
     * @logic Implementations MUST be safe to call with null/blank text (return as-is) and with
     *        a request whose context lacks an agent id (apply global policies or no-op).
     * @return scrubbed text; never null when {@code text} is non-null.
     */
    String scrub(String text, ChatClientRequest request);

    /** Identity scrubber for tests and unconfigured environments. */
    OutputPiiScrubber NO_OP = (text, request) -> text;
}
