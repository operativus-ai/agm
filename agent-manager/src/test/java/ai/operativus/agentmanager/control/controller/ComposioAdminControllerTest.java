package ai.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.dto.composio.ComposioActionConfigCreateRequest;
import ai.operativus.agentmanager.control.dto.composio.ComposioActionConfigResponse;
import ai.operativus.agentmanager.control.dto.composio.ComposioActionConfigUpdateRequest;
import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import ai.operativus.agentmanager.control.service.ComposioConfigService;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.exception.GlobalExceptionHandler;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.exception.StaleDataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Domain Responsibility: Pins {@link ComposioAdminController}'s request-routing, request-body
 *   validation, and HTTP status contracts. Uses standaloneSetup (no Spring Security filter chain)
 *   so {@code @PreAuthorize} is not evaluated here; authorization is covered by the integration
 *   suite in {@code ComposioAdminRuntimeTest}.
 *
 * <p>SecurityContext is manually populated so {@code callerOrgId()} / {@code callerUsername()}
 *   return deterministic values instead of "system".
 *
 * State: Stateless (each test gets a fresh MockMvc).
 */
@ExtendWith(MockitoExtension.class)
class ComposioAdminControllerTest {

    private static final String BASE = "/api/admin/composio/actions";
    private static final ObjectMapper OM = new ObjectMapper();

    @Mock private ComposioConfigService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ComposioAdminController controller = new ComposioAdminController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        populateSecurityContext("admin-user", "org-1", "ROLE_SUPER_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- GET /api/admin/composio/actions ---

    @Test
    void listActions_returns200WithActions() throws Exception {
        when(service.listActions()).thenReturn(List.of(
                response("gmail_send_email", "GMAIL_SEND_EMAIL", 1, true),
                response("slack_list_all_users", "SLACK_LIST_ALL_USERS", 2, false)));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].actionName").value("GMAIL_SEND_EMAIL"))
                .andExpect(jsonPath("$[1].actionName").value("SLACK_LIST_ALL_USERS"));
    }

    @Test
    void listActions_emptyRegistry_returns200EmptyList() throws Exception {
        when(service.listActions()).thenReturn(List.of());

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- POST /api/admin/composio/actions ---

    @Test
    void createAction_valid_returns201() throws Exception {
        ComposioActionConfigCreateRequest req = new ComposioActionConfigCreateRequest("GMAIL_SEND_EMAIL", 1, true);
        when(service.createAction(any(), eq("org-1"), eq("admin-user")))
                .thenReturn(response("gmail_send_email", "GMAIL_SEND_EMAIL", 1, true));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OM.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionName").value("GMAIL_SEND_EMAIL"))
                .andExpect(jsonPath("$.llmToolName").value("composio_gmail_send_email"));
    }

    @Test
    void createAction_duplicate_returns400() throws Exception {
        ComposioActionConfigCreateRequest req = new ComposioActionConfigCreateRequest("GMAIL_SEND_EMAIL", 1, true);
        when(service.createAction(any(), any(), any()))
                .thenThrow(new BusinessValidationException("Composio action already exists: GMAIL_SEND_EMAIL"));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OM.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAction_missingActionName_returns400() throws Exception {
        // Intentionally omit actionName to trigger @NotBlank validation
        String body = """
                {"tier": 1, "enabled": true}
                """;

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).createAction(any(), any(), any());
    }

    @Test
    void createAction_tierOutOfRange_returns400() throws Exception {
        // tier=5 violates @Max(3)
        String body = """
                {"actionName": "GMAIL_SEND_EMAIL", "tier": 5, "enabled": true}
                """;

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).createAction(any(), any(), any());
    }

    // --- PUT /api/admin/composio/actions/{id} ---

    @Test
    void updateAction_valid_returns200() throws Exception {
        ComposioActionConfigUpdateRequest req = new ComposioActionConfigUpdateRequest(2, false, 0);
        when(service.updateAction(eq("gmail_send_email"), any(), eq("org-1"), eq("admin-user")))
                .thenReturn(response("gmail_send_email", "GMAIL_SEND_EMAIL", 2, false));

        mockMvc.perform(put(BASE + "/gmail_send_email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OM.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value(2))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void updateAction_staleVersion_returns409() throws Exception {
        ComposioActionConfigUpdateRequest req = new ComposioActionConfigUpdateRequest(2, false, 0);
        when(service.updateAction(any(), any(), any(), any()))
                .thenThrow(new StaleDataException("ComposioActionConfig", "gmail_send_email"));

        mockMvc.perform(put(BASE + "/gmail_send_email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OM.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    void updateAction_notFound_returns404() throws Exception {
        ComposioActionConfigUpdateRequest req = new ComposioActionConfigUpdateRequest(1, true, 0);
        when(service.updateAction(any(), any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("ComposioActionConfig", "missing"));

        mockMvc.perform(put(BASE + "/missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OM.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // --- DELETE /api/admin/composio/actions/{id} ---

    @Test
    void deleteAction_found_returns204() throws Exception {
        doNothing().when(service).deleteAction(eq("gmail_send_email"), eq("org-1"), eq("admin-user"));

        mockMvc.perform(delete(BASE + "/gmail_send_email"))
                .andExpect(status().isNoContent());

        verify(service).deleteAction("gmail_send_email", "org-1", "admin-user");
    }

    @Test
    void deleteAction_notFound_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("ComposioActionConfig", "missing"))
                .when(service).deleteAction(eq("missing"), any(), any());

        mockMvc.perform(delete(BASE + "/missing"))
                .andExpect(status().isNotFound());
    }

    // --- helpers ---

    private static ComposioActionConfigResponse response(String id, String actionName, int tier, boolean enabled) {
        return new ComposioActionConfigResponse(id, actionName, "composio_" + id, tier, enabled, 0,
                null, null, "admin-user", null);
    }

    private static void populateSecurityContext(String username, String orgId, String role) {
        UserDetailsImpl principal = new UserDetailsImpl(
                UUID.randomUUID(), username, username + "@example.com", orgId,
                false, "password", List.of(new SimpleGrantedAuthority(role)));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
