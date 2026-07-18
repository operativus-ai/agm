package ai.operativus.agentmanager.integration.support;

import org.springframework.http.codec.ServerSentEvent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Domain Responsibility: Captures Server-Sent Events from a streaming endpoint into
 *   a synchronous list, so tests can assert on the full event sequence the server
 *   emitted (event names, ids, multi-line data payloads). Uses the Java 11 HttpClient
 *   directly because Spring's TestRestTemplate does not expose the response body as
 *   a streamable {@link InputStream} — its handlers buffer the whole response.
 * State: Stateless (the underlying {@link HttpClient} is reusable across calls).
 *
 * Lifecycle:
 *   - Each call opens a fresh connection; the client returns once the server closes
 *     the stream OR the request {@code timeout} elapses.
 *   - The parser dispatches one {@link ServerSentEvent} per blank-line-delimited
 *     block, accumulating multiple {@code data:} lines into a single payload joined
 *     by newlines (per the SSE spec).
 */
public final class SseTestClient {

    private final HttpClient http;
    private final String baseUrl;

    public SseTestClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<ServerSentEvent<String>> get(String path, String bearerToken, Duration timeout) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + bearerToken)
                .timeout(timeout)
                .GET()
                .build();
        return send(req);
    }

    public List<ServerSentEvent<String>> post(String path, String jsonBody, String bearerToken, Duration timeout) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return send(req);
    }

    /**
     * Captures the HTTP status code AND the parsed SSE event list for a POST. Use this when a
     * test needs to assert the failure path (e.g. 404 on unknown resource) where the server
     * short-circuits with an error body before emitting any SSE frames — {@link #post} alone
     * would collapse that into an empty event list and lose the fact that a 404 was returned.
     */
    public SseResponse postWithStatus(String path, String jsonBody, String bearerToken, Duration timeout) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            List<ServerSentEvent<String>> events = parse(resp.body());
            return new SseResponse(resp.statusCode(), events);
        } catch (Exception e) {
            throw new RuntimeException("SSE request failed: " + req.uri(), e);
        }
    }

    public record SseResponse(int statusCode, List<ServerSentEvent<String>> events) {}

    private List<ServerSentEvent<String>> send(HttpRequest req) {
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            return parse(resp.body());
        } catch (Exception e) {
            throw new RuntimeException("SSE request failed: " + req.uri(), e);
        }
    }

    private List<ServerSentEvent<String>> parse(InputStream body) throws Exception {
        List<ServerSentEvent<String>> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            String event = null;
            String id = null;
            StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0 || event != null || id != null) {
                        events.add(buildEvent(event, id, data.toString()));
                    }
                    event = null;
                    id = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append("\n");
                    data.append(line.substring(5).trim());
                }
            }
            if (data.length() > 0 || event != null || id != null) {
                events.add(buildEvent(event, id, data.toString()));
            }
        }
        return events;
    }

    private static ServerSentEvent<String> buildEvent(String event, String id, String data) {
        ServerSentEvent.Builder<String> b = ServerSentEvent.builder();
        if (event != null) b.event(event);
        if (id != null) b.id(id);
        b.data(data);
        return b.build();
    }
}
