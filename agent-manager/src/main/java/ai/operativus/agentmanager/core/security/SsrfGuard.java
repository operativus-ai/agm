package ai.operativus.agentmanager.core.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Domain Responsibility: Shared SSRF (Server-Side Request Forgery) guard used by every
 *   outbound-URL caller in the codebase. The guard rejects URLs that target:
 *   <ul>
 *     <li>Non-HTTP(S) schemes ({@code file:}, {@code javascript:}, {@code gopher:},
 *         {@code ftp:}, etc.)</li>
 *     <li>The {@code 0.0.0.0} any-local address (always rejected)</li>
 *     <li>Link-local addresses including the {@code 169.254/16} cloud-metadata range
 *         (always rejected — this is the highest-blast-radius SSRF target)</li>
 *     <li>Loopback addresses ({@code 127.0.0.0/8}, {@code ::1}, IPv4-mapped IPv6 loopback
 *         {@code ::ffff:127.0.0.0/104}, decimal-encoded {@code 2130706433}) and RFC-1918
 *         private ranges (10/8, 172.16/12, 192.168/16) — gated by the {@code allowLoopback}
 *         flag so test profiles can bind WireMock on localhost while production blocks</li>
 *   </ul>
 *
 *   <p><strong>Production callers</strong>:
 *   <ul>
 *     <li>{@code A2AController.registerPeer} — peer base URL</li>
 *     <li>{@code KnowledgeService.ingestUrlAsync / retryFailedUrlIngestion} — KB URL ingest</li>
 *     <li>{@code WebhookWorkflowStepExecutor.executeStep} — workflow step webhook URL</li>
 *   </ul>
 *
 *   <p><strong>Rationale for the IP-literal path</strong>: {@link InetAddress#getByName}
 *   doesn't accept decimal-encoded IPv4 forms like {@code http://2130706433/} (= 127.0.0.1)
 *   but curl, browsers, and Jsoup all do. The manual all-digits parser exists specifically
 *   for those bypass vectors that an earlier string-equals guard missed.
 *
 *   <p><strong>Why hostname targets aren't resolved</strong>: this guard parses IP literals
 *   only. A DNS resolution would add latency and create a TOCTOU window between the guard
 *   call and the actual HTTP fetch (DNS rebinding). Defense-in-depth against hostname-
 *   pointing-to-private-IP is the responsibility of the network layer or a separate
 *   resolved-at-request-time check.
 *
 * State: Stateless utility. All methods are static + pure (no I/O).
 */
public final class SsrfGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final String PRIVATE_REJECTION =
            "URL targets a private or loopback address; refusing to fetch";

    private SsrfGuard() {
        // utility — not instantiable
    }

    /**
     * @return {@code null} if {@code rawUrl} is safe to fetch; otherwise a human-readable
     *     rejection reason suitable for error responses.
     * @param rawUrl     the URL string from operator/user input (may be null/blank)
     * @param allowLoopback when {@code true}, loopback/RFC-1918/site-local addresses are
     *     ALLOWED — required for test profiles that need WireMock on {@code localhost}.
     *     Production callers MUST pass {@code false}. Even with {@code true}, the
     *     always-on rejections fire: non-HTTP(S) schemes, {@code 0.0.0.0}, link-local
     *     {@code 169.254/16} (cloud metadata).
     */
    public static String validate(String rawUrl, boolean allowLoopback) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "URL is required";
        }
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                return "Protocol must be http or https";
            }
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            // URI.getHost() returns IPv6 literals wrapped in square brackets (e.g. "[::1]");
            // strip them so InetAddress.getByName sees the canonical literal form.
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            if (host.isBlank()) {
                return "URL is missing a host";
            }
            if (!allowLoopback && host.equals("localhost")) {
                return PRIVATE_REJECTION;
            }
            InetAddress addr = parseIpLiteralOrNull(host);
            if (addr != null) {
                // Always-on rejections regardless of allowLoopback — these target the host's
                // network position even when the operator opted into permissive loopback for
                // test fixtures. isLinkLocalAddress catches the 169.254/16 cloud-metadata
                // range; isAnyLocalAddress catches 0.0.0.0 / ::
                if (addr.isAnyLocalAddress() || addr.isLinkLocalAddress()) {
                    return PRIVATE_REJECTION;
                }
                if (!allowLoopback && (addr.isLoopbackAddress() || addr.isSiteLocalAddress())) {
                    return PRIVATE_REJECTION;
                }
            }
            return null;
        } catch (IllegalArgumentException e) {
            return "Malformed URL: " + e.getMessage();
        }
    }

    /**
     * Parses {@code host} as a literal IP address WITHOUT a DNS lookup. Returns
     * {@code null} if {@code host} looks like a hostname (caller falls through to
     * the literal hostname checks above).
     *
     * <p>Handles three forms:
     * <ul>
     *   <li>IPv4 dotted-quad (e.g., {@code "127.0.0.1"}) — {@code getByName} parses without DNS.</li>
     *   <li>IPv6 literal (e.g., {@code "::1"}, {@code "::ffff:127.0.0.1"}) — same.</li>
     *   <li>Decimal-encoded IPv4 (e.g., {@code "2130706433"} → 127.0.0.1) — the JDK does
     *       NOT accept this via {@code getByName()}, but curl, browsers, Jsoup, and JDK
     *       {@code HttpClient} DO, making it a real bypass that the literal-string guards
     *       miss. Parsed manually here.</li>
     * </ul>
     */
    private static InetAddress parseIpLiteralOrNull(String host) {
        if (host.matches("^\\d+$")) {
            try {
                long val = Long.parseLong(host);
                if (val < 0L || val > 0xFFFFFFFFL) return null;
                byte[] octets = {
                        (byte) ((val >>> 24) & 0xFF),
                        (byte) ((val >>> 16) & 0xFF),
                        (byte) ((val >>> 8) & 0xFF),
                        (byte) (val & 0xFF),
                };
                return InetAddress.getByAddress(octets);
            } catch (NumberFormatException | UnknownHostException e) {
                return null;
            }
        }
        if (host.matches("^[\\d.]+$") || host.contains(":")) {
            try {
                return InetAddress.getByName(host);
            } catch (UnknownHostException e) {
                return null;
            }
        }
        return null;
    }
}
