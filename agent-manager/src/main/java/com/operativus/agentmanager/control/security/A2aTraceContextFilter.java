package com.operativus.agentmanager.control.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Domain Responsibility: Propagates OpenTelemetry Trace IDs across A2A networking
 * boundaries, stitching multi-platform workflow traces into a single correlated span tree.
 *
 * Gap 2.3 Implementation: Traces stored in {@code AgenticMemory} are currently indexed
 * by local {@code Session_ID} only. When executions span multiple AGM instances or peer
 * agents, there is no cross-boundary correlation. This filter bridges that gap by:
 *
 *  1. Extracting the W3C {@code traceparent} header (or legacy {@code X-B3-TraceId} / AGM
 *     proprietary {@code X-Trace-Id}) from inbound A2A requests.
 *  2. Binding the extracted trace ID to the SLF4J MDC under {@code traceId} so all log
 *     statements within the request processing scope carry the cross-system trace ID.
 *  3. Echoing the trace ID on the response via {@code X-Trace-Id} so the caller can
 *     correlate its own spans with AGM's processing window.
 *  4. Generating a fresh trace ID for requests that arrive without one (local-origin traffic).
 *
 * The filter does NOT depend on an OTel SDK being present — it is a lightweight propagation
 * shim. When a full OTel SDK is wired in (via {@code io.opentelemetry:opentelemetry-spring-boot-starter}),
 * its {@code TracingFilter} should be registered first (lower order) and this filter
 * becomes redundant. Until then, this provides observable cross-boundary tracing via MDC.
 *
 * W3C traceparent format: {@code 00-<traceId>-<spanId>-<flags>}
 * AGM extracts only the {@code traceId} segment (32 hex chars) for MDC binding.
 *
 * Architecture:
 * - {@code @Order(1)} — runs before JWT and API key filters so the trace ID is present
 *   in all downstream log statements regardless of auth outcome.
 * - No Spring context dependencies. No ApplicationEventPublisher.
 * - MDC entries are cleaned up in a finally block — no thread-local leakage.
 *
 * State: Stateless.
 */
@Component
@Order(1)
public class A2aTraceContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(A2aTraceContextFilter.class);

    /** W3C Trace Context propagation header (preferred). */
    public static final String W3C_TRACEPARENT_HEADER   = "traceparent";
    /** B3 single-header propagation (Zipkin legacy). */
    public static final String B3_TRACE_ID_HEADER       = "X-B3-TraceId";
    /** AGM proprietary trace header for non-standard peers. */
    public static final String AGM_TRACE_ID_HEADER      = "X-Trace-Id";

    /** MDC key under which the trace ID is stored for log correlation. */
    public static final String MDC_TRACE_ID_KEY         = "traceId";
    /** MDC key carrying the A2A initiating agent identity for log scoping. */
    public static final String MDC_A2A_INITIATOR_KEY    = "a2aInitiator";

    /** Request attribute key: downstream handlers can read the resolved trace ID. */
    public static final String TRACE_ID_REQUEST_ATTR    = "agm.traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        String initiator = request.getHeader("X-A2A-Initiating-Agent");

        try {
            MDC.put(MDC_TRACE_ID_KEY, traceId);
            if (StringUtils.hasText(initiator)) {
                MDC.put(MDC_A2A_INITIATOR_KEY, initiator);
            }
            request.setAttribute(TRACE_ID_REQUEST_ATTR, traceId);

            // Echo trace ID so the caller can correlate AGM spans with their own.
            // Only echo if the value is safe (hex chars only) — prevents header injection
            // if the resolved value somehow slips past extractW3cTraceId's length guard.
            if (isSafeTraceId(traceId)) {
                response.setHeader(AGM_TRACE_ID_HEADER, traceId);
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID_KEY);
            MDC.remove(MDC_A2A_INITIATOR_KEY);
        }
    }

    /**
     * @summary Resolves the effective trace ID for this request.
     * @logic Priority order:
     *   1. W3C {@code traceparent} — extracts the 32-char traceId segment.
     *   2. B3 {@code X-B3-TraceId} header.
     *   3. AGM proprietary {@code X-Trace-Id} header.
     *   4. Falls back to a freshly generated UUID if no propagated ID is present.
     */
    private String resolveTraceId(HttpServletRequest request) {
        // 1. W3C traceparent: "00-<traceId32hex>-<spanId16hex>-<flags>"
        String traceparent = request.getHeader(W3C_TRACEPARENT_HEADER);
        if (StringUtils.hasText(traceparent)) {
            String extracted = extractW3cTraceId(traceparent);
            if (extracted != null) {
                log.trace("A2aTraceFilter: W3C traceparent trace_id={}", extracted);
                return extracted;
            }
        }

        // 2. B3 trace ID
        String b3 = request.getHeader(B3_TRACE_ID_HEADER);
        if (StringUtils.hasText(b3)) {
            log.trace("A2aTraceFilter: B3 trace_id={}", b3);
            return b3.trim();
        }

        // 3. AGM proprietary header
        String agm = request.getHeader(AGM_TRACE_ID_HEADER);
        if (StringUtils.hasText(agm)) {
            log.trace("A2aTraceFilter: AGM trace_id={}", agm);
            return agm.trim();
        }

        // 4. Mint a fresh trace ID for locally-originated requests
        String fresh = UUID.randomUUID().toString().replace("-", "");
        log.trace("A2aTraceFilter: no propagated trace ID — generated fresh trace_id={}", fresh);
        return fresh;
    }

    /**
     * @summary Guards against header injection by validating the trace ID contains only
     *          lowercase hex characters (standard 32-char W3C format) or UUID-with-dashes.
     *          Rejects anything that could be used to inject additional header values.
     */
    private boolean isSafeTraceId(String traceId) {
        // N-4 Fix: Accept 16-char (64-bit) B3 trace IDs alongside 32-char W3C and 36-char UUID formats.
        return traceId != null && traceId.matches("^[a-f0-9\\-]{16,36}$");
    }

    /**
     * @summary Extracts the 32-character traceId from a W3C {@code traceparent} header.
     * @logic The format is {@code version-traceId-parentId-traceFlags}.
     *        The traceId is always the second dash-separated segment of exactly 32 hex chars.
     *        Returns null if the header does not conform to the expected format.
     */
    private String extractW3cTraceId(String traceparent) {
        try {
            String[] parts = traceparent.trim().split("-");
            if (parts.length >= 4 && parts[1].length() == 32) {
                return parts[1];
            }
        } catch (Exception e) {
            log.debug("A2aTraceFilter: malformed traceparent header '{}': {}", traceparent, e.getMessage());
        }
        return null;
    }
}
