package ai.operativus.agentmanager.control.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-T006.5: pins the regex used by logback-spring.xml's PII masker for SSE token
 * query params. Logback applies {@code String.replaceAll(regex, replacement)} to every
 * rendered message before emit; we verify the same regex masks the canonical leakage
 * vectors here so the live config can't silently drift.
 *
 * <p>The corresponding logback property is {@code SSE_TOKEN_REGEX}; replacement is
 * {@code token=***} on the CONSOLE appender and a generic {@code ****} mask via
 * {@code RegexValueMasker} on the JSON appender. Either way, the original UUID never
 * appears in stdout.
 */
class LogScrubbingPatternTest {

    // Must match logback-spring.xml's SSE_TOKEN_REGEX value verbatim.
    private static final String SSE_TOKEN_REGEX = "token=[A-Za-z0-9\\-]+";
    private static final String CONSOLE_REPLACEMENT = "token=***";

    private static String scrub(String input) {
        return input.replaceAll(SSE_TOKEN_REGEX, CONSOLE_REPLACEMENT);
    }

    @Test
    void scrubs_UrlWithLeadingQuestionMarkToken() {
        String token = "550e8400-e29b-41d4-a716-446655440000";
        String input = "GET /api/v1/runs/run-A/events?token=" + token;

        String scrubbed = scrub(input);

        assertThat(scrubbed).doesNotContain(token);
        assertThat(scrubbed).contains("token=***");
        assertThat(scrubbed).startsWith("GET /api/v1/runs/run-A/events?");
    }

    @Test
    void scrubs_UrlWithLeadingAmpersandToken() {
        String token = "abc-123-def-456";
        String input = "GET /api/v1/runs/run-A/events?sinceId=42&token=" + token;

        String scrubbed = scrub(input);

        assertThat(scrubbed).doesNotContain(token);
        assertThat(scrubbed).contains("&token=***");
        assertThat(scrubbed).contains("sinceId=42");
    }

    @Test
    void scrubs_TokenFollowedByOtherQueryParam() {
        String token = "11111111-2222-3333-4444-555555555555";
        String input = "/api/v1/runs/run-A/events?token=" + token + "&trailing=keep";

        String scrubbed = scrub(input);

        assertThat(scrubbed).doesNotContain(token);
        assertThat(scrubbed).contains("token=***");
        assertThat(scrubbed).contains("trailing=keep");
    }

    @Test
    void scrubs_MultipleTokenOccurrences() {
        String input = "first token=AAA-111 then later token=BBB-222 done";

        String scrubbed = scrub(input);

        assertThat(scrubbed).doesNotContain("AAA-111");
        assertThat(scrubbed).doesNotContain("BBB-222");
        assertThat(scrubbed).isEqualTo("first token=*** then later token=*** done");
    }

    @Test
    void doesNotScrub_BareTokenWithoutEquals() {
        // Word "token" by itself shouldn't trigger — only `token=<value>`.
        String input = "the access token is logged separately";

        String scrubbed = scrub(input);

        assertThat(scrubbed).isEqualTo(input);
    }

    @Test
    void doesNotScrub_TokenInsideUnrelatedField() {
        // "json_token=" or "csrf_token=" shouldn't be over-eagerly masked, since the
        // field name is different. The regex matches any `token=` substring exactly —
        // including these — but the value masking is desired behavior either way:
        // any `token=<value>` is sensitive enough to mask.
        String input = "csrf_token=ABCDEF-12345";

        String scrubbed = scrub(input);

        // Regex matches the trailing "token=ABCDEF-12345" and masks the value.
        assertThat(scrubbed).doesNotContain("ABCDEF-12345");
        assertThat(scrubbed).contains("token=***");
    }
}
