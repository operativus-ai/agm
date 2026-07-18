package com.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.service.AlertIntegrationService;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.core.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused mass-assignment coverage for {@link AlertIntegrationController}.
 *
 * <p>This is the highest-impact mass-assignment vector of the
 * #1016/1017/1018 arc. The {@code AlertIntegration} entity exposes setters
 * not only for {@code id} (the usual hijack-by-merge vector) and
 * {@code orgId}, but also for the server-managed retry state:
 * {@code retryCount}, {@code lastFailureAt}, {@code lastError},
 * {@code nextRetryAt}, {@code pendingPayload}, {@code pendingEventId}.
 *
 * <p>Worst-case exploit with the prior raw-entity binding:
 * <ol>
 *   <li>Attacker POSTs with {"id":"&lt;victim-integration-id&gt;",
 *       "endpointUrl":"http://attacker/...", "signingSecret":"&lt;attacker-secret&gt;",
 *       "pendingPayload":"&lt;arbitrary JSON&gt;", "nextRetryAt":"&lt;past&gt;",
 *       "retryCount":0}.
 *   <li>{@code save()} merges → UPDATES the victim's row. Victim's webhook now
 *       points at attacker's URL with attacker's signing secret.
 *   <li>The scheduled {@code redispatchPendingFailures} sweep picks up the
 *       newly-elapsed {@code nextRetryAt} and POSTs the attacker's
 *       {@code pendingPayload} to the (also attacker-controlled) endpoint.
 *       Full outbound spoofing on the victim's alert channel.
 * </ol>
 *
 * <p>The {@code AlertIntegrationRequest} DTO closes the vector — no slot for
 * id, orgId, createdAt, or any retry-state field; the controller maps only
 * the safe-field subset.
 */
@ExtendWith(MockitoExtension.class)
class AlertIntegrationControllerMassAssignmentTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AlertIntegrationService alertIntegrationService;

    @InjectMocks
    private AlertIntegrationController controller;

    private AlertIntegration sampleIntegration;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        sampleIntegration = new AlertIntegration();
        sampleIntegration.setId("server-generated-id");
        sampleIntegration.setName("PagerDuty critical");
        sampleIntegration.setType("WEBHOOK");
        sampleIntegration.setEndpointUrl("https://hooks.example.com/alert");
        sampleIntegration.setEnabled(true);
        sampleIntegration.setOrgId("DEFAULT_SYSTEM_ORG");
    }

    @Test
    void create_RejectsClientSuppliedId_AlwaysServerGenerated() throws Exception {
        when(alertIntegrationService.createIntegration(any())).thenReturn(sampleIntegration);

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "id", "victim-integration-id-from-another-tenant",
                "name", "attacker-controlled",
                "type", "WEBHOOK",
                "endpointUrl", "https://attacker.example.com/x",
                "enabled", true));

        mockMvc.perform(post("/api/alerts/integrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isCreated());

        ArgumentCaptor<AlertIntegration> captor = ArgumentCaptor.forClass(AlertIntegration.class);
        verify(alertIntegrationService).createIntegration(captor.capture());
        AlertIntegration passed = captor.getValue();

        assertNull(passed.getId(),
                "attacker-supplied id must NOT reach the service — id is server-generated. "
                        + "Without this guard, save() would merge on the attacker's id and "
                        + "overwrite the victim tenant's integration row (transferring the "
                        + "webhook URL + signing secret to attacker control).");
    }

    @Test
    void create_AttackerCannotMassAssignRetryState() throws Exception {
        when(alertIntegrationService.createIntegration(any())).thenReturn(sampleIntegration);

        // Worst-case mass-assignment payload: attacker tries to plant pendingPayload +
        // a past nextRetryAt so the scheduled redispatch sweep POSTs the payload immediately.
        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "name", "attacker-controlled",
                "type", "WEBHOOK",
                "endpointUrl", "https://attacker.example.com/x",
                "enabled", true,
                "pendingPayload", "{\"injected\":\"payload\"}",
                "nextRetryAt", "1970-01-01T00:00:00",
                "retryCount", 0,
                "lastError", "fake",
                "pendingEventId", "fake-event"));

        mockMvc.perform(post("/api/alerts/integrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isCreated());

        ArgumentCaptor<AlertIntegration> captor = ArgumentCaptor.forClass(AlertIntegration.class);
        verify(alertIntegrationService).createIntegration(captor.capture());
        AlertIntegration passed = captor.getValue();

        // Every retry-state field must arrive at its default (null / 0). The DTO has
        // no slot for any of them; the controller's applyRequest helper does not call
        // any retry-state setter.
        assertNull(passed.getPendingPayload(),
                "pendingPayload must NOT be mass-assignable — this field is set ONLY by "
                        + "AlertIntegrationService when a dispatch fails. Allowing client "
                        + "control turns it into a webhook-payload injection vector via "
                        + "the scheduled redispatch sweep.");
        assertNull(passed.getNextRetryAt(),
                "nextRetryAt must NOT be mass-assignable — combined with attacker-controlled "
                        + "pendingPayload, it lets the attacker force immediate re-dispatch.");
        assertEquals(0, passed.getRetryCount(),
                "retryCount must default to 0 — mass-assignment lets attacker reset the "
                        + "counter and bypass the max-attempts cap, enabling infinite retries.");
        assertNull(passed.getLastError(),
                "lastError must NOT be mass-assignable — it's a server-managed audit field.");
        assertNull(passed.getPendingEventId(),
                "pendingEventId must NOT be mass-assignable — it's a server-managed reference "
                        + "to the AlertEvent that triggered the failed dispatch.");
    }

    @Test
    void create_AttackerCannotMassAssignOrgId() throws Exception {
        when(alertIntegrationService.createIntegration(any())).thenReturn(sampleIntegration);

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "name", "attacker-controlled",
                "type", "WEBHOOK",
                "endpointUrl", "https://hooks.example.com/x",
                "enabled", true,
                "orgId", "VICTIM_ORG_ID"));

        mockMvc.perform(post("/api/alerts/integrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isCreated());

        ArgumentCaptor<AlertIntegration> captor = ArgumentCaptor.forClass(AlertIntegration.class);
        verify(alertIntegrationService).createIntegration(captor.capture());
        assertNotEquals("VICTIM_ORG_ID", captor.getValue().getOrgId(),
                "attacker-supplied orgId must NOT reach the entity. The service-layer "
                        + "overwrite is still in place as defense-in-depth, but the DTO "
                        + "ensures the entity arrives orgId=null so a future service "
                        + "refactor can't accidentally trust the inbound value.");
    }

    @Test
    void create_ValidationFailsOnBlankRequiredFields() throws Exception {
        // DTO has @NotBlank on name, type, endpointUrl. Missing all → 400.
        String invalidBody = objectMapper.writeValueAsString(Map.of(
                "enabled", true));

        mockMvc.perform(post("/api/alerts/integrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verify(alertIntegrationService, never()).createIntegration(any());
    }

    @Test
    void create_ValidationFailsOnUnknownTypeEnum() throws Exception {
        // Pattern allowlist on type: WEBHOOK/SLACK/PAGERDUTY only. Closes a separate
        // silent-acceptance bug — a non-WEBHOOK/non-SLACK/non-PAGERDUTY type would
        // bypass rejectIfSsrf (which only checks WEBHOOK/SLACK) AND skip dispatch
        // (the service's switch falls through).
        String invalidBody = objectMapper.writeValueAsString(Map.of(
                "name", "x",
                "type", "EVIL_NEW_TYPE",
                "endpointUrl", "https://hooks.example.com/x",
                "enabled", true));

        mockMvc.perform(post("/api/alerts/integrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verify(alertIntegrationService, never()).createIntegration(any());
    }

    @Test
    void update_AttackerCannotMassAssignRetryState() throws Exception {
        when(alertIntegrationService.updateIntegration(eq("integration-from-path"), any()))
                .thenReturn(sampleIntegration);

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "id", "different-integration-id",
                "name", "renamed",
                "type", "WEBHOOK",
                "endpointUrl", "https://attacker.example.com/x",
                "enabled", true,
                "pendingPayload", "{\"injected\":\"payload\"}",
                "nextRetryAt", "1970-01-01T00:00:00",
                "retryCount", 999));

        mockMvc.perform(put("/api/alerts/integrations/integration-from-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isOk());

        // The service receives the path variable as the id, never the body's id.
        verify(alertIntegrationService).updateIntegration(eq("integration-from-path"), any());

        ArgumentCaptor<AlertIntegration> captor = ArgumentCaptor.forClass(AlertIntegration.class);
        verify(alertIntegrationService).updateIntegration(eq("integration-from-path"), captor.capture());
        AlertIntegration passed = captor.getValue();
        assertNull(passed.getId(),
                "attacker-supplied id must NOT reach the service");
        assertNull(passed.getPendingPayload(),
                "attacker-supplied pendingPayload must NOT reach the service");
        assertNull(passed.getNextRetryAt(),
                "attacker-supplied nextRetryAt must NOT reach the service");
        assertEquals(0, passed.getRetryCount(),
                "attacker-supplied retryCount must NOT reach the service");
    }
}
