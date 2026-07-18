package ai.operativus.agentmanager.control.a2a;

import ai.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import ai.operativus.agentmanager.control.a2a.model.PeerCancellationNotify;
import ai.operativus.agentmanager.control.repository.A2aRemoteAgentRepository;
import ai.operativus.agentmanager.control.repository.A2aTaskEventRepository;
import ai.operativus.agentmanager.core.entity.A2aRemoteAgentEntity;
import ai.operativus.agentmanager.core.entity.A2aTaskEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: §22.5 cross-peer cancellation notification dispatcher.
 * When an inbound A2A task is cancelled locally (caller-initiated DELETE or internal
 * policy), this service reaches back to the originating peer so they can audit the
 * propagation and release any client-side resources.
 *
 * Flow (outbound side):
 *   A2ATaskExecutor interrupts the local virtual thread → emits CANCELLED SSE →
 *   calls {@link #notifyCancellation(String, String, String)} → fire-and-forget
 *   virtual thread POSTs {@code {peer.baseUrl}/api/v1/a2a/peers/cancel-notify} with
 *   {@link PeerCancellationNotify} body and {@code X-A2A-Api-Key} carrying the
 *   peer's stored outbound API key (auto-decrypted by {@code OutboundApiKeyConverter}).
 *
 * Peer lookup is global (not org-scoped) matching the {@code PeerHealthMonitor}
 * precedent — cancellation notify is a system-level signal and peer identity is
 * already unique across the fleet via {@code remote_agent_id}.
 *
 * Best-effort semantics:
 * - If no peer is registered for {@code initiatingAgentId}, the call no-ops silently.
 *   The outer cancel path still writes the CANCELLED audit row via
 *   {@code A2ATaskExecutor.audit}, so the local forensic trail is preserved.
 * - HTTP failures (timeout, 4xx, 5xx) are logged and an audit row is appended with
 *   status=FAILED and an error message. The local cancel is never rolled back.
 * - Dispatch runs on a fresh virtual thread so the {@code A2ATaskExecutor} cancel
 *   branch can complete its cleanup without blocking on outbound I/O.
 *
 * Architecture:
 * - Constructor injection only.
 * - Shared {@code RestTemplate} bean from {@code WebConfig} (5 s connect / 15 s read).
 *
 * State: Stateless.
 */
@Service
public class PeerCancellationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PeerCancellationDispatcher.class);

    static final String CANCEL_NOTIFY_PATH = "/api/v1/a2a/peers/cancel-notify";

    private final A2aRemoteAgentRepository peerRepository;
    private final A2aTaskEventRepository taskEventRepository;
    private final RestTemplate restTemplate;

    public PeerCancellationDispatcher(
            A2aRemoteAgentRepository peerRepository,
            A2aTaskEventRepository taskEventRepository,
            RestTemplate restTemplate) {
        this.peerRepository      = peerRepository;
        this.taskEventRepository = taskEventRepository;
        this.restTemplate        = restTemplate;
    }

    /**
     * @summary Fires a best-effort cancellation notification to the peer identified by
     *          {@code initiatingAgentId}, if one is registered locally.
     * @logic Resolves the peer via {@code A2aRemoteAgentRepository.findByRemoteAgentId}.
     *        If unknown, logs at INFO and returns — the local cancel still succeeded.
     *        Otherwise spawns a virtual thread that POSTs the notify payload and writes
     *        an audit row recording dispatch success or failure.
     */
    public void notifyCancellation(String taskId, String initiatingAgentId, String reason) {
        if (taskId == null || initiatingAgentId == null) {
            return;
        }

        A2aRemoteAgentEntity peer = peerRepository.findByRemoteAgentId(initiatingAgentId).orElse(null);
        if (peer == null) {
            log.info("PeerCancellationDispatcher: no peer registered for initiatingAgentId={} — "
                + "skipping notify for taskId={}", initiatingAgentId, taskId);
            return;
        }

        String baseUrl       = peer.getBaseUrl();
        String outboundKey   = peer.getOutboundApiKey();
        String targetAgentId = peer.getRemoteAgentId();

        Thread.ofVirtual()
            .name("a2a-cancel-notify-" + taskId)
            .start(() -> dispatch(taskId, targetAgentId, baseUrl, outboundKey, reason));
    }

    private void dispatch(String taskId, String targetAgentId,
                          String baseUrl, String outboundKey, String reason) {
        String url = baseUrl + CANCEL_NOTIFY_PATH;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (outboundKey != null && !outboundKey.isBlank()) {
                headers.add("X-A2A-Api-Key", outboundKey);
            }
            HttpEntity<PeerCancellationNotify> request = new HttpEntity<>(
                new PeerCancellationNotify(taskId, reason), headers);

            restTemplate.postForEntity(url, request, Void.class);
            log.info("PeerCancellationDispatcher: notify delivered taskId={} peer={}", taskId, url);
            writeAudit(taskId, targetAgentId, A2aTaskStatus.CANCELLED,
                "notify-dispatched: " + (reason != null ? reason : ""), null);
        } catch (Exception e) {
            log.warn("PeerCancellationDispatcher: notify failed taskId={} peer={}: {}",
                taskId, url, e.getMessage());
            writeAudit(taskId, targetAgentId, A2aTaskStatus.FAILED,
                "notify-dispatch-failed", e.getMessage());
        }
    }

    private void writeAudit(String taskId, String targetAgentId,
                            A2aTaskStatus status, String message, String errorDetail) {
        try {
            A2aTaskEventEntity event = new A2aTaskEventEntity();
            event.setTaskId(taskId);
            event.setTargetAgentId(targetAgentId != null ? targetAgentId : "peer-notify-outbound");
            event.setStatus(status);
            event.setMessage(message);
            event.setErrorDetail(errorDetail);
            event.setEventTs(LocalDateTime.now());
            taskEventRepository.save(event);
        } catch (Exception ex) {
            log.warn("PeerCancellationDispatcher: failed to persist audit taskId={} status={}: {}",
                taskId, status, ex.getMessage());
        }
    }
}
