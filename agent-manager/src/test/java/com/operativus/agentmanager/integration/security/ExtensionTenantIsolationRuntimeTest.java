package com.operativus.agentmanager.integration.security;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code ExtensionController} tenant-scoping fix (#1132).
 *
 * <p>Pre-fix the {@code extensions} table had no {@code org_id}, so the registry was global:
 * any org's admin could list, update, or delete any other org's extensions, and the MCP
 * connection pool exposed every org's tools to every tenant's agents. Post-fix each row is
 * owned by the registering org; reads are scoped ({@code findByOrgId}) and per-id mutations
 * resolve via {@code findByIdAndOrgId} so a cross-org id is indistinguishable from missing
 * (404 / silent 204 — no existence leak).
 */
@Tag("integration")
public class ExtensionTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Test
    void extensionsAreTenantScoped_orgACannotSeeOrMutateOrgBExtensions() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders orgA = registerLoginWithOrg("ext-iso-A-" + tag, "org-ext-A-" + tag);
        HttpHeaders orgB = registerLoginWithOrg("ext-iso-B-" + tag, "org-ext-B-" + tag);

        // org A registers an MCP extension (controller stamps it with org A).
        Map<String, Object> body = new HashMap<>();
        body.put("name", "A's MCP " + tag);
        body.put("type", "MCP");
        body.put("url", "https://mcp-a.example.com/sse");
        body.put("active", true);
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.POST, new HttpEntity<>(body, orgA), JSON_MAP);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        String extId = String.valueOf(created.getBody().get("id"));
        assertNotNull(extId);

        // 1. org B's list must NOT contain org A's extension.
        assertFalse(listIds(orgB).contains(extId), "org B must not see org A's extension in the list");

        // 2. org A's list DOES contain it.
        assertTrue(listIds(orgA).contains(extId), "org A must see its own extension");

        // 3. Cross-org PUT → 404 (resolved before version/SSRF checks, so org scope is the cause).
        Map<String, Object> update = new HashMap<>(body);
        update.put("version", 0);
        ResponseEntity<Void> crossPut = rest.exchange(
                url("/api/v1/extensions/" + extId), HttpMethod.PUT, new HttpEntity<>(update, orgB), Void.class);
        assertEquals(HttpStatus.NOT_FOUND, crossPut.getStatusCode(), "PUT on org A's extension as org B must 404");

        // 4. Cross-org DELETE → 204 no-op; the row survives (org A still sees it).
        ResponseEntity<Void> crossDelete = rest.exchange(
                url("/api/v1/extensions/" + extId), HttpMethod.DELETE, new HttpEntity<>(orgB), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, crossDelete.getStatusCode());
        assertTrue(listIds(orgA).contains(extId), "org A's extension must survive org B's cross-org delete");

        // 5. org A CAN delete its own extension.
        ResponseEntity<Void> ownDelete = rest.exchange(
                url("/api/v1/extensions/" + extId), HttpMethod.DELETE, new HttpEntity<>(orgA), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, ownDelete.getStatusCode());
        assertFalse(listIds(orgA).contains(extId), "org A's extension is gone after its own delete");
    }

    /** Ids of the DB-registered extensions visible to the given caller (excludes NATIVE_SPI rows with no DB id collision). */
    private List<String> listIds(HttpHeaders auth) {
        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return resp.getBody().stream().map(e -> String.valueOf(e.get("id"))).toList();
    }
}
