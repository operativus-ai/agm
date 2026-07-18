package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.core.model.enums.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: Manages per-document SSE emitters for ingestion status streaming.
 * State: Stateful (ConcurrentHashMap of active emitters, bounded by concurrent uploads).
 *
 * Single-instance limitation: In a multi-node deployment the subscriber must hit the same
 * node as the ingestion worker. This is acceptable for the current single-instance topology.
 */
@Service
public class IngestionStatusService {

    private static final Logger log = LoggerFactory.getLogger(IngestionStatusService.class);
    private static final long TIMEOUT_MS = 90_000L;

    private final ConcurrentHashMap<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public record IngestionStatusEvent(UUID documentId, String status, String message) {}

    public SseEmitter register(UUID documentId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.put(documentId, emitter);
        emitter.onCompletion(() -> emitters.remove(documentId));
        emitter.onTimeout(() -> {
            emitters.remove(documentId);
            emitter.complete();
        });
        emitter.onError(ex -> emitters.remove(documentId));
        return emitter;
    }

    public void emit(UUID documentId, String status, String message) {
        SseEmitter emitter = emitters.get(documentId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("ingestion-status")
                    .data(new IngestionStatusEvent(documentId, status, message)));
            if (JobStatus.COMPLETED.getValue().equals(status) || JobStatus.FAILED.getValue().equals(status)) {
                emitter.complete();
                emitters.remove(documentId);
            }
        } catch (IOException e) {
            log.debug("SSE sink closed for documentId={}. Event dropped: {}", documentId, status);
            emitters.remove(documentId);
        }
    }
}
