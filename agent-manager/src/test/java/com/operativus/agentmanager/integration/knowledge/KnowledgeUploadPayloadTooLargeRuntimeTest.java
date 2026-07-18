package com.operativus.agentmanager.integration.knowledge;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pin the 413 contract on
 * {@code GlobalExceptionHandler.handlePayloadTooLarge} added by PR #935 (Bug #15).
 *
 * Pre-fix, a multipart upload exceeding
 * {@code spring.servlet.multipart.max-file-size} (or {@code max-request-size})
 * surfaced as either a 500 (caught by the catch-all) or — worse — a misleading
 * 404 when Tomcat rejected the body before route matching. Operators couldn't
 * distinguish "request too big" from "endpoint not found", and clients had no
 * machine-readable hint at the configured limit.
 *
 * Post-fix the handler maps {@link org.springframework.web.multipart.MaxUploadSizeExceededException}
 * to 413 (RFC 9110 §15.5.14 — Content Too Large), returning an RFC 7807
 * ProblemDetail body with {@code type=urn:problem-type:payload-too-large} and a
 * {@code maxBytes} property carrying the configured limit so clients can
 * surface an actionable message.
 *
 * Force the limit to a tiny value via {@code @TestPropertySource} so a small
 * fixture payload (~2KB) reliably trips it without exercising real Tomcat
 * timeouts or stressing the test container.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
@TestPropertySource(properties = {
        "spring.servlet.multipart.max-file-size=1KB",
        "spring.servlet.multipart.max-request-size=2KB"
})
public class KnowledgeUploadPayloadTooLargeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void uploadOversizedFile_returns413WithProblemDetailAndMaxBytes() {
        HttpHeaders auth = authenticatedHeaders("kb-413-probe");
        String kbId = createKnowledgeBase(auth, "K2 413 fixture");

        // 4KB payload — comfortably over the 1KB max-file-size set above.
        byte[] oversizedBytes = new byte[4 * 1024];
        java.util.Arrays.fill(oversizedBytes, (byte) 'A');

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", filePart("oversized.txt", "text/plain", oversizedBytes));
        body.add("knowledgeBaseId", kbId);

        HttpHeaders multipartHeaders = new HttpHeaders();
        multipartHeaders.addAll(auth);
        multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/knowledge/upload-batch"), HttpMethod.POST,
                new HttpEntity<>(body, multipartHeaders), JSON_MAP);

        // PR #935 contract: 413, NOT 500 (catch-all) or 404 (pre-route Tomcat reject).
        // Spring 4 renamed HttpStatus.PAYLOAD_TOO_LARGE → CONTENT_TOO_LARGE (RFC 9110 §15.5.14);
        // assert on the raw code to stay stable across enum-rename churn.
        assertThat(resp.getStatusCode().value())
                .as("PR #935: oversize multipart upload must surface as HTTP 413, "
                        + "not the pre-fix 500 / misleading 404. Got: " + resp.getStatusCode())
                .isEqualTo(413);

        // RFC 7807 problem detail body
        assertThat(resp.getHeaders().getContentType())
                .as("response must be application/problem+json for client RFC 7807 parsing")
                .isNotNull()
                .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        Map<String, Object> problem = resp.getBody();
        assertThat(problem).as("413 must carry a ProblemDetail body").isNotNull();
        assertThat(problem.get("title")).isEqualTo("Payload Too Large");
        assertThat(problem.get("type")).isEqualTo("urn:problem-type:payload-too-large");

        // PR #935 conditional property: the handler sets `maxBytes` only when
        // `ex.getMaxUploadSize() > 0`. Spring's MaxUploadSizeExceededException
        // doesn't always propagate the configured limit (Tomcat-level rejects
        // may construct the exception without it). When present, it must equal
        // the @TestPropertySource override; absence is allowed by the
        // production-code guard but we cannot pin a value in that case.
        if (problem.containsKey("maxBytes")) {
            Number maxBytes = (Number) problem.get("maxBytes");
            assertThat(maxBytes.longValue())
                    .as("when maxBytes is set, it must reflect spring.servlet.multipart.max-file-size=1KB")
                    .isEqualTo(1024L);
        }
    }

    // ─── helpers (mirror KnowledgeMultipartUploadRuntimeTest conventions) ─────

    private String createKnowledgeBase(HttpHeaders auth, String name) {
        Map<String, Object> kbBody = Map.of(
                "name", name + " " + UUID.randomUUID(),
                "description", "PR #935 retro-test fixture");
        ResponseEntity<Map<String, Object>> kbCreated = rest.exchange(
                url("/api/v1/knowledge-bases"), HttpMethod.POST,
                new HttpEntity<>(kbBody, auth), JSON_MAP);
        return (String) kbCreated.getBody().get("id");
    }

    private static ByteArrayResource filePart(String filename, String contentType, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-413-1234",
                List.of("ROLE_USER"));
    }
}
