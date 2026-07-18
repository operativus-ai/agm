package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.dto.composio.ComposioActionConfigCreateRequest;
import ai.operativus.agentmanager.control.dto.composio.ComposioActionConfigResponse;
import ai.operativus.agentmanager.control.dto.composio.ComposioActionConfigUpdateRequest;
import ai.operativus.agentmanager.control.repository.ComposioActionConfigRepository;
import ai.operativus.agentmanager.control.repository.ComposioConnectionConfigRepository;
import ai.operativus.agentmanager.core.entity.ComposioActionConfig;
import ai.operativus.agentmanager.core.event.ComposioConfigChangedEvent;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.exception.StaleDataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Domain Responsibility: Pins {@link ComposioConfigService}'s CRUD, event-publish,
 *   optimistic-locking, and audit-delegation contracts.
 *
 * <p>Pure JUnit + Mockito — no Spring context, no Testcontainers.
 * State: Stateless (each test uses a fresh service instance via @BeforeEach).
 */
@ExtendWith(MockitoExtension.class)
class ComposioConfigServiceTest {

    @Mock private ComposioActionConfigRepository repository;
    @Mock private ComposioConnectionConfigRepository connectionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SystemAuditService systemAuditService;

    private ComposioConfigService service;

    @BeforeEach
    void setUp() {
        service = new ComposioConfigService(repository, connectionRepository, eventPublisher, systemAuditService);
    }

    // --- listActions ---

    @Test
    void listActions_returnsMappedDtos() {
        when(repository.findAll()).thenReturn(List.of(
                action("gmail_send_email", "GMAIL_SEND_EMAIL", 1, true),
                action("slack_list_all_users", "SLACK_LIST_ALL_USERS", 2, false)));

        List<ComposioActionConfigResponse> result = service.listActions();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).actionName()).isEqualTo("GMAIL_SEND_EMAIL");
        assertThat(result.get(1).actionName()).isEqualTo("SLACK_LIST_ALL_USERS");
    }

    // --- createAction ---

    @Test
    void createAction_newAction_savesAndPublishesEvent() {
        ComposioActionConfigCreateRequest req = new ComposioActionConfigCreateRequest("GMAIL_SEND_EMAIL", 1, true);
        when(repository.existsById("gmail_send_email")).thenReturn(false);
        ComposioActionConfig saved = action("gmail_send_email", "GMAIL_SEND_EMAIL", 1, true);
        when(repository.save(any())).thenReturn(saved);

        ComposioActionConfigResponse result = service.createAction(req, "org-1", "admin");

        assertThat(result.actionName()).isEqualTo("GMAIL_SEND_EMAIL");
        assertThat(result.llmToolName()).isEqualTo("composio_gmail_send_email");

        ArgumentCaptor<ComposioConfigChangedEvent> eventCaptor = ArgumentCaptor.forClass(ComposioConfigChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().reason()).isEqualTo("action_create");

        verify(systemAuditService).record(eq("org-1"), eq("admin"),
                eq("COMPOSIO_ACTION_CREATE"), eq("composio_action_config"), eq("gmail_send_email"),
                eq("POST"), eq("/api/admin/composio/actions"), eq((Integer) 201));
    }

    @Test
    void createAction_duplicate_throwsBusinessValidationException() {
        ComposioActionConfigCreateRequest req = new ComposioActionConfigCreateRequest("GMAIL_SEND_EMAIL", 1, true);
        when(repository.existsById("gmail_send_email")).thenReturn(true);

        assertThatThrownBy(() -> service.createAction(req, "org-1", "admin"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("GMAIL_SEND_EMAIL");

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createAction_normalizesActionNameToUppercase() {
        ComposioActionConfigCreateRequest req = new ComposioActionConfigCreateRequest("  gmail_send_email  ", 2, true);
        when(repository.existsById("gmail_send_email")).thenReturn(false);
        ComposioActionConfig saved = action("gmail_send_email", "GMAIL_SEND_EMAIL", 2, true);
        when(repository.save(any())).thenReturn(saved);

        ComposioActionConfigResponse result = service.createAction(req, "org-1", "admin");

        assertThat(result.actionName()).isEqualTo("GMAIL_SEND_EMAIL");
    }

    // --- updateAction ---

    @Test
    void updateAction_correctVersion_savesAndPublishesEvent() {
        ComposioActionConfig existing = action("gmail_send_email", "GMAIL_SEND_EMAIL", 1, true);
        existing.setVersion(0);
        when(repository.findById("gmail_send_email")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenReturn(existing);

        ComposioActionConfigUpdateRequest req = new ComposioActionConfigUpdateRequest(3, false, 0);
        ComposioActionConfigResponse result = service.updateAction("gmail_send_email", req, "org-1", "admin");

        assertThat(result).isNotNull();
        ArgumentCaptor<Object> updateEventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(updateEventCaptor.capture());
        assertThat(updateEventCaptor.getValue()).isInstanceOf(ComposioConfigChangedEvent.class);
        assertThat(((ComposioConfigChangedEvent) updateEventCaptor.getValue()).reason()).isEqualTo("action_update");
        verify(systemAuditService).record(eq("org-1"), eq("admin"),
                eq("COMPOSIO_ACTION_UPDATE"), eq("composio_action_config"), eq("gmail_send_email"),
                eq("PUT"), eq("/api/admin/composio/actions/gmail_send_email"), eq((Integer) 200));
    }

    @Test
    void updateAction_staleVersion_throwsStaleDataException() {
        ComposioActionConfig existing = action("gmail_send_email", "GMAIL_SEND_EMAIL", 1, true);
        existing.setVersion(5);
        when(repository.findById("gmail_send_email")).thenReturn(Optional.of(existing));

        ComposioActionConfigUpdateRequest req = new ComposioActionConfigUpdateRequest(2, true, 0); // wrong version

        assertThatThrownBy(() -> service.updateAction("gmail_send_email", req, "org-1", "admin"))
                .isInstanceOf(StaleDataException.class);

        verify(repository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateAction_notFound_throwsResourceNotFoundException() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAction("missing",
                new ComposioActionConfigUpdateRequest(1, true, 0), "org-1", "admin"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- deleteAction ---

    @Test
    void deleteAction_found_deletesAndPublishesEvent() {
        ComposioActionConfig existing = action("gmail_send_email", "GMAIL_SEND_EMAIL", 1, true);
        when(repository.findById("gmail_send_email")).thenReturn(Optional.of(existing));

        service.deleteAction("gmail_send_email", "org-1", "admin");

        verify(repository).delete(existing);
        ArgumentCaptor<Object> deleteEventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(deleteEventCaptor.capture());
        assertThat(deleteEventCaptor.getValue()).isInstanceOf(ComposioConfigChangedEvent.class);
        assertThat(((ComposioConfigChangedEvent) deleteEventCaptor.getValue()).reason()).isEqualTo("action_delete");
        verify(systemAuditService).record(eq("org-1"), eq("admin"),
                eq("COMPOSIO_ACTION_DELETE"), eq("composio_action_config"), eq("gmail_send_email"),
                eq("DELETE"), eq("/api/admin/composio/actions/gmail_send_email"), eq((Integer) 204));
    }

    @Test
    void deleteAction_notFound_throwsResourceNotFoundException() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAction("missing", "org-1", "admin"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // --- bulkImportActions (gap #21) ---

    @Test
    void bulkImportActions_emptyList_neitherPublishesNorTouchesRepo() {
        ComposioConfigService.BulkImportResult result =
                service.bulkImportActions(List.of(), false, 2, "org-1", "admin");

        assertThat(result.created()).isEmpty();
        assertThat(result.skipped()).isEmpty();
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        // Audit still fires — operators want to see "import ran with 0 actions" as a row.
        verify(systemAuditService).record(any(), any(), eq("COMPOSIO_ACTION_BULK_IMPORT"),
                eq("composio_action_config"), any(), eq("POST"),
                eq("/api/admin/composio/catalog/import"), eq((Integer) 200));
    }

    @Test
    void bulkImportActions_freshActions_areCreated_tierDefaultsTo2() {
        when(repository.findById("slack_send_message")).thenReturn(Optional.empty());
        when(repository.findById("github_create_issue")).thenReturn(Optional.empty());
        when(repository.save(any(ComposioActionConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ComposioConfigService.BulkImportResult result = service.bulkImportActions(
                List.of("SLACK_SEND_MESSAGE", "github_create_issue"),
                false, null, "org-1", "admin");

        assertThat(result.created()).containsExactlyInAnyOrder("SLACK_SEND_MESSAGE", "GITHUB_CREATE_ISSUE");
        assertThat(result.skipped()).isEmpty();
        verify(eventPublisher).publishEvent(any(ComposioConfigChangedEvent.class));

        ArgumentCaptor<ComposioActionConfig> captor = ArgumentCaptor.forClass(ComposioActionConfig.class);
        verify(repository, times(2)).save(captor.capture());
        // Tier defaults to 2 (HITL-gated) on null input — the safe-by-default choice.
        assertThat(captor.getAllValues()).allMatch(c -> c.getTier() == 2 && c.isEnabled());
        // Names always normalize to UPPERCASE.
        assertThat(captor.getAllValues()).extracting(ComposioActionConfig::getActionName)
                .containsExactlyInAnyOrder("SLACK_SEND_MESSAGE", "GITHUB_CREATE_ISSUE");
    }

    @Test
    void bulkImportActions_existingActions_areSkipped_byDefault() {
        when(repository.findById("slack_send_message"))
                .thenReturn(Optional.of(action("slack_send_message", "SLACK_SEND_MESSAGE", 2, true)));
        when(repository.findById("github_create_issue"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ComposioActionConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ComposioConfigService.BulkImportResult result = service.bulkImportActions(
                List.of("SLACK_SEND_MESSAGE", "GITHUB_CREATE_ISSUE"),
                false, 2, "org-1", "admin");

        assertThat(result.created()).containsExactly("GITHUB_CREATE_ISSUE");
        assertThat(result.skipped()).containsExactly("SLACK_SEND_MESSAGE");
        verify(repository, times(1)).save(any(ComposioActionConfig.class));
    }

    @Test
    void bulkImportActions_overwriteExisting_reEnablesDisabledRows() {
        ComposioActionConfig disabled = action("slack_send_message", "SLACK_SEND_MESSAGE", 2, false);
        when(repository.findById("slack_send_message")).thenReturn(Optional.of(disabled));
        when(repository.saveAndFlush(any(ComposioActionConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ComposioConfigService.BulkImportResult result = service.bulkImportActions(
                List.of("SLACK_SEND_MESSAGE"), true, 2, "org-1", "admin");

        assertThat(result.created()).containsExactly("SLACK_SEND_MESSAGE");
        assertThat(result.skipped()).isEmpty();
        assertThat(disabled.isEnabled()).isTrue();
        verify(repository).saveAndFlush(disabled);
    }

    @Test
    void bulkImportActions_overwriteExisting_alreadyEnabledRow_isStillSkipped() {
        // Overwrite only re-enables disabled rows — an already-enabled row is a no-op.
        ComposioActionConfig enabled = action("slack_send_message", "SLACK_SEND_MESSAGE", 2, true);
        when(repository.findById("slack_send_message")).thenReturn(Optional.of(enabled));

        ComposioConfigService.BulkImportResult result = service.bulkImportActions(
                List.of("SLACK_SEND_MESSAGE"), true, 2, "org-1", "admin");

        assertThat(result.created()).isEmpty();
        assertThat(result.skipped()).containsExactly("SLACK_SEND_MESSAGE");
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void bulkImportActions_nullsAndBlanksFilteredOut() {
        when(repository.findById("good")).thenReturn(Optional.empty());
        when(repository.save(any(ComposioActionConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        java.util.List<String> noisy = new java.util.ArrayList<>();
        noisy.add(null);
        noisy.add("");
        noisy.add("   ");
        noisy.add("GOOD");

        ComposioConfigService.BulkImportResult result =
                service.bulkImportActions(noisy, false, 2, "org-1", "admin");
        assertThat(result.created()).containsExactly("GOOD");
        verify(repository, times(1)).save(any(ComposioActionConfig.class));
    }

    @Test
    void bulkImportActions_perActionFailure_isolatedToFailuresList() {
        when(repository.findById("explode")).thenReturn(Optional.empty());
        when(repository.findById("ok")).thenReturn(Optional.empty());
        when(repository.save(any(ComposioActionConfig.class)))
                .thenAnswer(inv -> {
                    ComposioActionConfig c = inv.getArgument(0);
                    if ("EXPLODE".equals(c.getActionName())) {
                        throw new RuntimeException("simulated DB failure");
                    }
                    return c;
                });

        ComposioConfigService.BulkImportResult result = service.bulkImportActions(
                List.of("EXPLODE", "OK"), false, 2, "org-1", "admin");

        assertThat(result.created()).containsExactly("OK");
        assertThat(result.skipped()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).getKey()).isEqualTo("EXPLODE");
        assertThat(result.failures().get(0).getValue()).contains("simulated DB failure");
    }

    @Test
    void bulkImportActions_noActionsCreated_doesNotPublishEvent() {
        // All inputs map to skipped (existing enabled rows, overwrite=false). No created
        // rows → no point churning the registry; suppress the event.
        when(repository.findById("a")).thenReturn(Optional.of(action("a", "A", 2, true)));
        when(repository.findById("b")).thenReturn(Optional.of(action("b", "B", 2, true)));

        ComposioConfigService.BulkImportResult result = service.bulkImportActions(
                List.of("A", "B"), false, 2, "org-1", "admin");

        assertThat(result.created()).isEmpty();
        assertThat(result.skipped()).containsExactlyInAnyOrder("A", "B");
        verify(eventPublisher, never()).publishEvent(any());
    }

    // --- helpers ---

    private static ComposioActionConfig action(String id, String actionName, int tier, boolean enabled) {
        ComposioActionConfig c = new ComposioActionConfig();
        c.setId(id);
        c.setActionName(actionName);
        c.setLlmToolName("composio_" + id);
        c.setTier(tier);
        c.setEnabled(enabled);
        c.setVersion(0);
        return c;
    }
}
