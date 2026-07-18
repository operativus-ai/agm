package com.operativus.agentmanager.integration.dispatch;

import com.operativus.agentmanager.control.dto.OrgRoutingConfigRequest;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that with {@code agm.universal-dispatch.enabled=false} (production default)
 * the {@code OrgRoutingConfigAdminController} bean is NOT registered, so all
 * routing-config endpoints return 404 regardless of caller role. Mirrors the
 * Skills flag-disabled contract from PR-1c.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
@TestPropertySource(properties = "agm.universal-dispatch.enabled=false")
public class OrgRoutingConfigFlagDisabledRuntimeTest extends BaseIntegrationTest {

    @Test
    void getConfig_flagDisabled_returns404() {
        HttpHeaders auth = adminHeaders("rcfg-flag-get");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.GET,
                new HttpEntity<>(auth), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void upsertConfig_flagDisabled_returns404() {
        HttpHeaders auth = adminHeaders("rcfg-flag-put");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.PUT,
                new HttpEntity<>(new OrgRoutingConfigRequest(null, null, true, true, null, null), auth),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void deleteConfig_flagDisabled_returns404() {
        HttpHeaders auth = adminHeaders("rcfg-flag-del");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.DELETE,
                new HttpEntity<>(auth), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local",
                "pass-rcfg-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
