package ai.operativus.agentmanager.control.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Domain Responsibility: Bounded fetcher for media URLs supplied on agent-run requests.
 *   The {@code mapMedia} helpers (e.g.
 *   {@code AgentsController}) previously handed a raw {@code UrlResource} to Spring AI's
 *   media pipeline; Spring AI then eagerly fetches the URL bytes to ship into the LLM
 *   payload with NO size cap, NO connect timeout, and NO read timeout. That made every
 *   authenticated tenant user a one-request DoS vector:
 *   <ul>
 *     <li><strong>Oversized payload</strong> — attacker hosts a multi-GB file on a
 *         public URL; AGM tries to fetch the entire body into memory → OOM / GC stall.</li>
 *     <li><strong>Slow-loris</strong> — attacker hosts a slow-response endpoint; AGM's
 *         request thread blocks indefinitely waiting for bytes that never come.</li>
 *     <li><strong>Connection-exhaustion fan-out</strong> — request with N media URLs all
 *         pointing at slow endpoints → N pinned threads + N open sockets per request.</li>
 *   </ul>
 *
 *   <p>This fetcher closes those vectors by pulling the URL through a {@link HttpClient}
 *   with explicit connect + read timeouts and a byte-counted streaming read that fails
 *   fast on any of:
 *   <ol>
 *     <li>Advertised {@code Content-Length} exceeds the configured cap (fail-fast before
 *         reading any body bytes).</li>
 *     <li>Streamed body exceeds the cap mid-read (peer-set Content-Length is advisory;
 *         servers can lie or use chunked encoding).</li>
 *     <li>Connect or read timeout fires.</li>
 *     <li>Response status is non-2xx.</li>
 *   </ol>
 *   On success, the fetched bytes are returned and the caller wraps them in a
 *   {@code ByteArrayResource} — Spring AI then never sees a URL, so its eager-fetch path
 *   is bypassed entirely.
 *
 *   <p><strong>Redirect handling.</strong> {@code Redirect.NEVER} — following redirects
 *   would let an attacker bypass the {@link SsrfGuard} check that the caller ran on the
 *   original URL (3xx → http://169.254.169.254/...). A 3xx response yields a
 *   {@link MediaFetchException} with the redirect chain truncated.
 *
 *   <p><strong>DNS rebinding.</strong> {@link SsrfGuard} only parses IP literals — a
 *   hostname that DNS-resolves to a private IP would pass the guard and this fetcher
 *   would still send the request. Mitigation lives at the network layer (egress firewall
 *   blocking RFC-1918 destinations) per the guard's documented contract. The size +
 *   timeout caps here still limit blast radius even if the request reaches a private
 *   target.
 *
 * State: Stateless. Holds a configured {@link HttpClient} and three immutable limits.
 */
@Component
public class MediaUrlFetcher {

    private static final Logger log = LoggerFactory.getLogger(MediaUrlFetcher.class);
    private static final int BUFFER_BYTES = 8192;

    private final HttpClient client;
    private final Duration readTimeout;
    private final long maxBytes;

    public MediaUrlFetcher(
            @Value("${agentmanager.media.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${agentmanager.media.read-timeout-ms:30000}") long readTimeoutMs,
            @Value("${agentmanager.media.max-bytes:10485760}") long maxBytes) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
        this.maxBytes = maxBytes;
    }

    /**
     * Convenience constructor for tests that want to set limits directly.
     * Production should rely on the {@code @Value} primary constructor.
     */
    public static MediaUrlFetcher forLimits(Duration connectTimeout, Duration readTimeout, long maxBytes) {
        return new MediaUrlFetcher(
                connectTimeout.toMillis(),
                readTimeout.toMillis(),
                maxBytes);
    }

    public byte[] fetch(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(readTimeout)
                .GET()
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new MediaFetchException("Media URL fetch timed out after "
                    + readTimeout.toMillis() + "ms");
        } catch (IOException e) {
            throw new MediaFetchException("Media URL fetch failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaFetchException("Media URL fetch interrupted");
        }

        int status = resp.statusCode();
        if (status >= 300 && status < 400) {
            throw new MediaFetchException("Media URL returned redirect (HTTP " + status
                    + "); redirects are refused to prevent SSRF-guard bypass");
        }
        if (status / 100 != 2) {
            throw new MediaFetchException("Media URL returned HTTP " + status);
        }

        // Fail-fast on advertised Content-Length larger than the cap. Skipping this would
        // still be safe (the byte-counter below would catch it mid-read), but failing
        // before any body read is cheaper and yields a clearer error message.
        resp.headers().firstValueAsLong("Content-Length").ifPresent(len -> {
            if (len > maxBytes) {
                throw new MediaFetchException("Media URL Content-Length " + len
                        + " exceeds cap of " + maxBytes + " bytes");
            }
        });

        try (InputStream in = resp.body();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[BUFFER_BYTES];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > maxBytes) {
                    throw new MediaFetchException("Media URL payload exceeded cap of "
                            + maxBytes + " bytes (peer ignored Content-Length or used chunked encoding)");
                }
                out.write(buf, 0, n);
            }
            if (log.isDebugEnabled()) {
                log.debug("Fetched media URL: {} bytes from {}", total, url);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new MediaFetchException("Media URL read failed: " + e.getMessage());
        }
    }

    /** Thrown when a media URL fetch is refused or fails. Always caller-actionable. */
    public static class MediaFetchException extends RuntimeException {
        public MediaFetchException(String message) {
            super(message);
        }
    }
}
