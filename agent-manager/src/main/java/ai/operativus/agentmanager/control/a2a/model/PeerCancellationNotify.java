package ai.operativus.agentmanager.control.a2a.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain Responsibility: Wire format for the cross-peer cancellation notification
 * exchanged via {@code POST /api/v1/a2a/peers/cancel-notify}.
 *
 * Gap §22.5: AGM previously interrupted the local virtual thread on cancel without
 * informing the originating peer. This record carries the minimum payload needed
 * for the initiating peer to audit and react — the correlation {@code taskId} and
 * a human-readable reason.
 *
 * State: Stateless (Data Carrier).
 */
public record PeerCancellationNotify(
    @NotBlank String taskId,
    String reason
) {}
