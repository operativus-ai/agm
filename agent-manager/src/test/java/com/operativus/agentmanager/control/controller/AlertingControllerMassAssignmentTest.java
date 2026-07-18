package com.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.service.AlertingService;
import com.operativus.agentmanager.core.entity.AlertRule;
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
 * Focused mass-assignment coverage for
 * {@link AlertingController#createRule(com.operativus.agentmanager.control.dto.AlertRuleRequest)}
 * and {@link AlertingController#updateRule(String, com.operativus.agentmanager.control.dto.AlertRuleRequest)}.
 *
 * <p>Threat shape: previously the controller bound {@code @RequestBody AlertRule}.
 * The entity's id is application-managed; the service generated a UUID only when
 * the body's id was null/blank. A caller could supply an existing rule's id and
 * have Spring Data {@code save()} merge → UPDATE that row with attacker-controlled
 * threshold / condition / orgId (the service overwrites orgId post-bind, so the
 * row is also transferred between tenants).
 *
 * <p>The {@code AlertRuleRequest} DTO closes the vector — it has no id slot, so
 * the entity that reaches the service always has {@code id=null} and gets a
 * fresh server-generated UUID.
 */
@ExtendWith(MockitoExtension.class)
class AlertingControllerMassAssignmentTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AlertingService alertingService;

    @InjectMocks
    private AlertingController controller;

    private AlertRule sampleRule;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        sampleRule = new AlertRule();
        sampleRule.setId("server-generated-id");
        sampleRule.setName("Hot CPU");
        sampleRule.setMetricName("system.cpu.usage");
        sampleRule.setCondition("GT");
        sampleRule.setThreshold(0.9);
        sampleRule.setSeverity("WARNING");
        sampleRule.setEnabled(true);
        sampleRule.setOrgId("DEFAULT_SYSTEM_ORG");
    }

    @Test
    void createRule_RejectsClientSuppliedId_AlwaysServerGenerated() throws Exception {
        when(alertingService.createRule(any())).thenReturn(sampleRule);

        // Attacker payload: legit fields + an id pointing at another tenant's rule row.
        // AlertRuleRequest has no id slot, so Jackson drops it. The entity that reaches
        // the service has id=null, which the service then UUID-generates.
        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "id", "victim-rule-id-from-another-tenant",
                "name", "attacker-controlled",
                "metricName", "system.cpu.usage",
                "condition", "GT",
                "threshold", 0.0,
                "windowSeconds", 60,
                "severity", "WARNING",
                "enabled", true));

        mockMvc.perform(post("/api/alerts/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isCreated());

        ArgumentCaptor<AlertRule> captor = ArgumentCaptor.forClass(AlertRule.class);
        verify(alertingService).createRule(captor.capture());
        AlertRule passed = captor.getValue();

        assertNull(passed.getId(),
                "attacker-supplied id must NOT reach the service — id is server-generated. "
                        + "Without this guard, AlertingService.save() would merge on the "
                        + "attacker's id and overwrite the victim tenant's rule row.");
    }

    @Test
    void createRule_AttackerCannotMassAssignOrgId() throws Exception {
        when(alertingService.createRule(any())).thenReturn(sampleRule);

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "name", "attacker-controlled",
                "metricName", "system.cpu.usage",
                "condition", "GT",
                "threshold", 0.0,
                "windowSeconds", 60,
                "severity", "WARNING",
                "enabled", true,
                "orgId", "VICTIM_ORG_ID"));

        mockMvc.perform(post("/api/alerts/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isCreated());

        ArgumentCaptor<AlertRule> captor = ArgumentCaptor.forClass(AlertRule.class);
        verify(alertingService).createRule(captor.capture());
        // The entity reaching the service must NOT carry the attacker's orgId.
        // The service then stamps orgId from AgentContextHolder — but we want the
        // entity to arrive with orgId=null so there's no risk of a future service
        // refactor accidentally trusting the inbound value.
        assertNotEquals("VICTIM_ORG_ID", captor.getValue().getOrgId(),
                "attacker-supplied orgId must NOT reach the entity. "
                        + "Even with the existing service-layer overwrite, defense in depth "
                        + "requires the entity to arrive orgId=null.");
    }

    @Test
    void createRule_ValidationFailsOnBlankRequiredFields() throws Exception {
        // DTO has @NotBlank on name, metricName, condition, severity. Missing all → 400.
        String invalidBody = objectMapper.writeValueAsString(Map.of(
                "threshold", 0.0,
                "windowSeconds", 60,
                "enabled", true));

        mockMvc.perform(post("/api/alerts/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verify(alertingService, never()).createRule(any());
    }

    @Test
    void createRule_ValidationFailsOnUnknownConditionEnum() throws Exception {
        // Pattern allowlist on condition: only GT/GTE/LT/LTE/EQ. Attacker injecting
        // an arbitrary enum value (would otherwise be silently accepted and break
        // the scheduled evaluator's switch default).
        String invalidBody = objectMapper.writeValueAsString(Map.of(
                "name", "Hot CPU",
                "metricName", "system.cpu.usage",
                "condition", "DROP_TABLE",
                "threshold", 0.0,
                "windowSeconds", 60,
                "severity", "WARNING",
                "enabled", true));

        mockMvc.perform(post("/api/alerts/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verify(alertingService, never()).createRule(any());
    }

    @Test
    void updateRule_AttackerCannotMassAssignId_OnlyPathVariableUsed() throws Exception {
        when(alertingService.updateRule(eq("rule-from-path"), any())).thenReturn(sampleRule);

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "id", "different-rule-id-to-try-to-steer-the-update",
                "name", "renamed-by-attacker",
                "metricName", "system.cpu.usage",
                "condition", "GT",
                "threshold", 1.0,
                "windowSeconds", 60,
                "severity", "WARNING",
                "enabled", true,
                "orgId", "VICTIM_ORG_ID"));

        mockMvc.perform(put("/api/alerts/rules/rule-from-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isOk());

        // The service receives the path variable as the id, never the body's id.
        verify(alertingService).updateRule(eq("rule-from-path"), any());

        ArgumentCaptor<AlertRule> captor = ArgumentCaptor.forClass(AlertRule.class);
        verify(alertingService).updateRule(eq("rule-from-path"), captor.capture());
        AlertRule passed = captor.getValue();
        assertNull(passed.getId(),
                "attacker-supplied id in update body must NOT reach the service — "
                        + "ruleId comes from the path variable only");
        assertNotEquals("VICTIM_ORG_ID", passed.getOrgId(),
                "attacker-supplied orgId in update body must NOT reach the entity");
    }
}
