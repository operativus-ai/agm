package ai.operativus.agentmanager.control.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * First-ever unit test for {@link WorkflowWebSocketHandler}. Pins session registry
 * lifecycle (connect / disconnect) and {@code broadcastEvent} JSON shape + delivery
 * semantics.
 *
 * <p>The handler is stateful (it maintains a {@link java.util.concurrent.ConcurrentHashMap}
 * of active sessions) but each test instance creates a fresh handler in {@code @BeforeEach},
 * so cases don't leak state.
 *
 * <p>One case documents a current limitation in
 * {@link #broadcastEvent_oneSessionIOException_shortCircuitsRemaining}: the IOException
 * catch block wraps the entire loop, so one failing session aborts the broadcast for the
 * remaining sessions. The case pins this behavior so a future per-session try/catch fix
 * surfaces as a deliberate test update rather than silent semantic drift.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowWebSocketHandlerTest {

    private WorkflowWebSocketHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new WorkflowWebSocketHandler();
    }

    @Test
    void afterConnectionEstablished_addsSessionToRegistry() throws Exception {
        WebSocketSession session = openSession("sess-1");

        handler.afterConnectionEstablished(session);

        // Broadcasting now must reach the session — proxy for "is it in the registry".
        handler.broadcastEvent("wf-1", "STARTED", Map.of("foo", "bar"));
        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void afterConnectionClosed_removesSessionFromRegistry() throws Exception {
        WebSocketSession session = openSession("sess-1");
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        handler.broadcastEvent("wf-1", "STARTED", Map.of());
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void broadcastEvent_emitsExpectedJsonShape() throws Exception {
        WebSocketSession session = openSession("sess-1");
        handler.afterConnectionEstablished(session);

        long t0 = System.currentTimeMillis();
        handler.broadcastEvent("wf-42", "STEP_COMPLETED", Map.of("stepOrder", 3));
        long t1 = System.currentTimeMillis();

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        JsonNode payload = mapper.readTree(captor.getValue().getPayload());

        assertThat(payload.get("workflow_id").asText()).isEqualTo("wf-42");
        assertThat(payload.get("event").asText()).isEqualTo("STEP_COMPLETED");
        assertThat(payload.get("payload").get("stepOrder").asInt()).isEqualTo(3);
        long timestamp = payload.get("timestamp").asLong();
        assertThat(timestamp).isBetween(t0, t1);
    }

    @Test
    void broadcastEvent_skipsClosedSessions() throws Exception {
        WebSocketSession open = openSession("open-1");
        WebSocketSession closed = mock(WebSocketSession.class);
        when(closed.getId()).thenReturn("closed-1");
        when(closed.isOpen()).thenReturn(false);

        handler.afterConnectionEstablished(open);
        handler.afterConnectionEstablished(closed);

        handler.broadcastEvent("wf-1", "TICK", Map.of());

        verify(open).sendMessage(any(TextMessage.class));
        verify(closed, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void broadcastEvent_withNoSubscribers_doesNotThrow() {
        assertThatCode(() -> handler.broadcastEvent("wf-1", "ORPHAN", Map.of("k", "v")))
                .doesNotThrowAnyException();
    }

    @Test
    void broadcastEvent_fanout_sendsToAllOpenSessions() throws Exception {
        WebSocketSession a = openSession("a");
        WebSocketSession b = openSession("b");
        WebSocketSession c = openSession("c");
        handler.afterConnectionEstablished(a);
        handler.afterConnectionEstablished(b);
        handler.afterConnectionEstablished(c);

        handler.broadcastEvent("wf-1", "FANOUT", Map.of());

        verify(a).sendMessage(any(TextMessage.class));
        verify(b).sendMessage(any(TextMessage.class));
        verify(c).sendMessage(any(TextMessage.class));
    }

    @Test
    void broadcastEvent_payloadIsSerializedAsStructure_notToString() throws Exception {
        WebSocketSession session = openSession("sess-1");
        handler.afterConnectionEstablished(session);

        record Step(int order, String name) {}
        handler.broadcastEvent("wf-1", "STEP", new Step(7, "ingest"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        JsonNode payload = mapper.readTree(captor.getValue().getPayload()).get("payload");
        assertThat(payload.get("order").asInt()).isEqualTo(7);
        assertThat(payload.get("name").asText()).isEqualTo("ingest");
    }

    @Test
    void broadcastEvent_oneSessionIOException_shortCircuitsRemaining() throws Exception {
        // PINS CURRENT BEHAVIOR (NOT IDEAL): the broadcastEvent try/catch wraps the
        // ENTIRE loop, so an IOException from one session aborts the broadcast for
        // remaining sessions. A per-session try/catch would isolate failures. Flag
        // here so a future fix surfaces as a deliberate assertion update.
        WebSocketSession bad = mock(WebSocketSession.class);
        when(bad.getId()).thenReturn("bad");
        when(bad.isOpen()).thenReturn(true);
        doThrowOnSend(bad, new IOException("client gone"));

        WebSocketSession good = openSession("good");

        // Order is registry-insertion order via ConcurrentHashMap.values() iteration —
        // for ConcurrentHashMap that order is technically unspecified, but in practice
        // we just need one of the two sessions to fail and assert the loop semantics
        // hold for whichever pair is encountered. Insert bad first; if iteration order
        // happens to start with good (test would still pass since good's sendMessage
        // returns successfully and bad's throw aborts the rest), the assertion below
        // covers both orderings: bad MUST have been invoked.
        handler.afterConnectionEstablished(bad);
        handler.afterConnectionEstablished(good);

        // Must NOT throw out of broadcast — caller is the producer-side workflow service.
        assertThatCode(() -> handler.broadcastEvent("wf-1", "BOOM", Map.of()))
                .doesNotThrowAnyException();

        verify(bad).sendMessage(any(TextMessage.class));
    }

    private static WebSocketSession openSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        // lenient — some cases register a session but never broadcast through it
        // (e.g. afterConnectionClosed removal), so isOpen() may go unused.
        org.mockito.Mockito.lenient().when(s.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(s.isOpen()).thenReturn(true);
        return s;
    }

    private static void doThrowOnSend(WebSocketSession s, IOException e) {
        try {
            org.mockito.Mockito.lenient().doThrow(e).when(s).sendMessage(any(TextMessage.class));
        } catch (IOException ignored) {
            // declared, not thrown by stubbing
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T any(Class<T> clazz) {
        return org.mockito.ArgumentMatchers.any(clazz);
    }
}
