package com.operativus.agentmanager.arch;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Coverage manifest for every controller method gated by
 *   {@code @PreAuthorize("hasRole('ADMIN')")} (method-level OR inherited from class-level).
 *   Each admin endpoint must appear on {@link #ADMIN_ENDPOINT_COVERAGE} tagged with the
 *   test file that exercises its authz path. A new admin endpoint that ships without a
 *   coverage entry fails the build (forward guard); a stale entry that no longer matches
 *   any method also fails (stale-entry guard).
 *
 *   <p>The allowlist serves a dual purpose: (a) preventing silently-shipped admin endpoints
 *   that lack authz runtime coverage; (b) a greppable manifest of which runtime test owns
 *   each endpoint. Run {@code grep "matrix:" AdminEndpointCoverageArchTest.java} for the
 *   list of endpoints owned by {@code AdminEndpointAuthzRuntimeTest}.
 *
 * State: Stateless. Pure-classpath unit test (no Spring context, no Postgres). Sibling to
 *   {@link ControllerContractArchTest} — same scanning pattern, same ratchet-down semantics.
 *
 * <p>Why an allowlist instead of "every admin endpoint MUST hit AdminEndpointAuthzRuntimeTest":
 *   focused tests (e.g. {@code EvaluationControllerAuthzRuntimeTest}) legitimately own a
 *   sub-tree's authz contract because the matrix would otherwise need fixture knowledge
 *   that's local to that subsystem. The allowlist tag tells you which file to look in.
 */
public class AdminEndpointCoverageArchTest {

    private static final String CONTROLLER_BASE_PACKAGE = "com.operativus.agentmanager";

    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, DeleteMapping.class, PatchMapping.class);

    /** Matches {@code @PreAuthorize} expressions that gate on ROLE_ADMIN. */
    private static final Pattern ADMIN_GATE = Pattern.compile(
            "hasRole\\(\\s*['\"]ADMIN['\"]\\s*\\)" +
                    "|hasAuthority\\(\\s*['\"]ROLE_ADMIN['\"]\\s*\\)");

    /**
     * Coverage manifest. Format: "fully.qualified.ClassName#methodName  // <test-source>"
     * <p>Tags:
     * <ul>
     *   <li><b>matrix:</b> — covered by {@code AdminEndpointAuthzRuntimeTest.ADMIN_ENDPOINTS}</li>
     *   <li><b>matrix-super:</b> — covered by {@code AdminEndpointAuthzRuntimeTest.SUPER_ADMIN_ENDPOINTS}</li>
     *   <li><b>focused:</b> — a dedicated authz test owns this endpoint</li>
     * </ul>
     */
    private static final Set<String> ADMIN_ENDPOINT_COVERAGE = Set.of(
            // === Class-level @PreAuthorize("hasRole('ADMIN')") — UserAdminController ===
            "com.operativus.agentmanager.control.controller.UserAdminController#listUsers",                                  // focused: UserAdminRuntimeTest
            "com.operativus.agentmanager.control.controller.UserAdminController#createUser",                                 // focused: UserAdminRuntimeTest
            "com.operativus.agentmanager.control.controller.UserAdminController#updateUser",                                 // focused: UserAdminCrudRuntimeTest + UserAdminLastAdminAndSelfDeleteGuardsRuntimeTest
            "com.operativus.agentmanager.control.controller.UserAdminController#resetPassword",                              // focused: UserAdminCrudRuntimeTest
            "com.operativus.agentmanager.control.controller.UserAdminController#deleteUser",                                 // focused: UserAdminCrudRuntimeTest + UserAdminLastAdminAndSelfDeleteGuardsRuntimeTest
            "com.operativus.agentmanager.control.controller.UserAdminController#bulkCreate",                                 // focused: UserAdminRuntimeTest + UserAdminDeleteCascadeAndBulkRuntimeTest + UserAdminBulkWithinBatchCollisionRuntimeTest

            // === BackgroundJobController — admin matrix + focused jobs tests ===
            "com.operativus.agentmanager.control.controller.BackgroundJobController#list",                                   // matrix
            "com.operativus.agentmanager.control.controller.BackgroundJobController#statusSummary",                          // matrix
            "com.operativus.agentmanager.control.controller.BackgroundJobController#retry",                                  // matrix + focused: BackgroundJobRetryEndpointMatrixRuntimeTest
            "com.operativus.agentmanager.control.controller.BackgroundJobController#getPauseState",                          // focused: BackgroundJobAdminPauseResumeRuntimeTest
            "com.operativus.agentmanager.control.controller.BackgroundJobController#pause",                                  // focused: BackgroundJobAdminPauseResumeRuntimeTest
            "com.operativus.agentmanager.control.controller.BackgroundJobController#resume",                                 // focused: BackgroundJobAdminPauseResumeRuntimeTest

            // === Live admin endpoints under AdminEndpointAuthzRuntimeTest ADMIN_ENDPOINTS matrix ===
            "com.operativus.agentmanager.control.controller.AuditLogController#exportAuditLogsCsv",                          // matrix
            "com.operativus.agentmanager.control.controller.AuditLogController#listAuditLogs",                               // matrix
            "com.operativus.agentmanager.control.controller.ComposioConnectionAdminController#deleteConnection",             // matrix
            "com.operativus.agentmanager.control.controller.ComposioConnectionAdminController#getConnection",                // matrix
            "com.operativus.agentmanager.control.controller.ComposioConnectionAdminController#upsertConnection",             // matrix
            "com.operativus.agentmanager.control.controller.DataRetentionController#getPolicies",                            // matrix
            "com.operativus.agentmanager.control.controller.DataRetentionController#triggerPurge",                           // matrix
            "com.operativus.agentmanager.control.controller.McpLifecycleController#reconnectServer",                         // matrix
            "com.operativus.agentmanager.control.controller.SchedulesController#getSpotBatches",                             // matrix
            "com.operativus.agentmanager.control.controller.SystemAuditLogController#list",                                  // matrix
            // Per-(org, provider) LLM API key admin surface — DB-only key resolution path.
            "com.operativus.agentmanager.control.controller.ProviderCredentialAdminController#list",                          // matrix
            "com.operativus.agentmanager.control.controller.ProviderCredentialAdminController#get",                           // matrix
            "com.operativus.agentmanager.control.controller.ProviderCredentialAdminController#upsert",                        // matrix
            "com.operativus.agentmanager.control.controller.ProviderCredentialAdminController#update",                        // matrix
            "com.operativus.agentmanager.control.controller.ProviderCredentialAdminController#delete",                        // matrix
            "com.operativus.agentmanager.control.controller.ProviderCredentialAdminController#test",                          // matrix
            // Live model-catalog passthrough — admin-only.
            "com.operativus.agentmanager.control.controller.ModelCatalogController#getCatalog",                              // matrix
            // pgvector re-embed backfill — rebuilds vector_store rows under the current embedding model.
            "com.operativus.agentmanager.control.controller.EmbeddingBackfillAdminController#backfill",                      // focused: EmbeddingBackfillRuntimeTest

            // === Live admin endpoints under AdminEndpointAuthzRuntimeTest SUPER_ADMIN_ENDPOINTS matrix ===
            // (none of the current super-admin matrix endpoints map to controllers via hasRole('ADMIN') —
            // the super-admin endpoints use ComposioAdminController + IncidentResponseController#haltAllRuns
            // gated by a different SpEL expression; they're not flagged by ADMIN_GATE.)

            // === Other live admin-gated endpoints — TODO: each needs focused authz test ===
            "com.operativus.agentmanager.control.controller.ApprovalsController#bulkResolve",                                // focused: MiscAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.ApprovalsController#resolveApproval",                            // focused: MiscAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.ApprovalsController#decide",                                     // focused: HumanReviewDecideEndpointRuntimeTest (REQ-HR-5)
            "com.operativus.agentmanager.control.controller.ComplianceController#eraseUserData",                             // focused: ComplianceAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.ComplianceController#exportUserData",                            // focused: ComplianceAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.ComplianceController#listErasureRequests",                       // focused: ComplianceAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.ComplianceController#submitErasureRequest",                      // focused: ComplianceAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.EvaluationController#addCaseToSuite",                            // focused: EvaluationAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.EvaluationController#createSuite",                               // focused: EvaluationAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.EvaluationController#deleteCase",                                // focused: EvaluationAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.EvaluationController#deleteSuite",                               // focused: EvaluationAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.EvaluationController#runSuite",                                  // focused: EvaluationControllerAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.ExtensionController#deleteExtension",                            // focused: ExtensionAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.ExtensionController#registerExtension",                          // focused: ExtensionAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.ExtensionController#updateExtension",                            // focused: ExtensionAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.ExtensionController#validateConnection",                         // focused: ExtensionAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.IncidentResponseController#quarantine",                          // focused: MiscAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.IncidentResponseController#unquarantine",                        // focused: MiscAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.MemoryController#addMemory",                                     // focused: MemoryAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.MemoryController#deleteMemories",                                // focused: MemoryAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.MemoryController#optimizeMemories",                              // focused: MemoryAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.SchedulesController#createSchedule",                             // focused: SchedulesAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.SchedulesController#deleteSchedule",                             // focused: SchedulesAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.SchedulesController#triggerSchedule",                            // focused: SchedulesAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.SchedulesController#updateSchedule",                             // focused: SchedulesAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.SettingsController#updateSettings",                              // focused: MiscAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#addWorkflowEdge",                            // focused: WorkflowEdgesEndpointRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#deleteWorkflowEdge",                         // focused: WorkflowEdgesEndpointRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#updateWorkflowEdge",                         // focused: WorkflowEdgesEndpointRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#saveWorkflowLayout",                        // focused: WorkflowEdgesEndpointRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#updateWorkflowStep",                         // focused: WorkflowStepUpdateRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#addWorkflowStep",                            // focused: WorkflowsAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#cloneWorkflow",                              // focused: WorkflowsAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#createWorkflow",                             // focused: WorkflowsAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#deleteWorkflow",                             // focused: WorkflowsAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#deleteWorkflowStep",                         // focused: WorkflowsAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.WorkflowsController#updateWorkflow",                             // focused: WorkflowsAdminAuthzRuntimeTest

            // === FinOpsAdminController — class-level @PreAuthorize('hasRole(ADMIN)') (PR #810) ===
            "com.operativus.agentmanager.control.controller.FinOpsAdminController#getActiveAnomalies",                       // focused: FinOpsRuntimeTest
            "com.operativus.agentmanager.control.controller.FinOpsAdminController#getActiveBurnRates",                       // focused: FinOpsRuntimeTest
            "com.operativus.agentmanager.control.controller.FinOpsAdminController#getCacheImpactSeries",                     // focused: FinOpsRuntimeTest
            "com.operativus.agentmanager.control.controller.FinOpsAdminController#getCostAllocations",                       // focused: FinOpsRuntimeTest
            "com.operativus.agentmanager.control.controller.FinOpsAdminController#getCostAllocationsByModel",                // focused: FinOpsRuntimeTest
            "com.operativus.agentmanager.control.controller.FinOpsAdminController#getHistoricalTrends",                      // focused: FinOpsRuntimeTest
            "com.operativus.agentmanager.control.controller.FinOpsAdminController#getRoiStats",                              // focused: FinOpsRuntimeTest
            "com.operativus.agentmanager.control.controller.FinOpsAdminController#getValuationRates",                        // focused: FinOpsRuntimeTest
            "com.operativus.agentmanager.control.controller.FinOpsAdminController#updateBaseline",                           // focused: FinOpsAdminMutationAuthzRuntimeTest
            // FinOpsAdminController#updateValuationRate removed from this manifest — gated as SUPER_ADMIN (not ADMIN)
            // by PR #1010; covered by AdminEndpointAuthzRuntimeTest.SUPER_ADMIN_ENDPOINTS matrix instead.
            "com.operativus.agentmanager.control.controller.MonitoringController#getGlobalStats",                            // focused: MonitoringControllerRuntimeTest
            "com.operativus.agentmanager.control.controller.MonitoringController#getSandboxCapabilities",                    // focused: MonitoringControllerRuntimeTest
            "com.operativus.agentmanager.control.controller.MonitoringController#getThreatEvents",                           // focused: MonitoringControllerRuntimeTest
            // === PiiAdminController — gated by PR #968. Service layer has NO tenant filter
            //     today; class-level @PreAuthorize is the minimum-viable fix. 401/403/gate-clear
            //     matrix exercised by PiiAdminAuthzRuntimeTest.
            "com.operativus.agentmanager.compute.api.PiiAdminController#listAllPolicies",                                    // focused: PiiAdminAuthzRuntimeTest
            "com.operativus.agentmanager.compute.api.PiiAdminController#createPolicy",                                       // focused: PiiAdminAuthzRuntimeTest
            "com.operativus.agentmanager.compute.api.PiiAdminController#deletePolicy",                                       // focused: PiiAdminAuthzRuntimeTest
            "com.operativus.agentmanager.compute.api.PiiAdminController#getAgentBindings",                                   // focused: PiiAdminAuthzRuntimeTest
            "com.operativus.agentmanager.compute.api.PiiAdminController#bindPolicy",                                         // focused: PiiAdminAuthzRuntimeTest
            "com.operativus.agentmanager.compute.api.PiiAdminController#unbindPolicy",                                       // focused: PiiAdminAuthzRuntimeTest
            "com.operativus.agentmanager.compute.api.PiiAdminController#getAuditLog",                                        // focused: PiiAuditLogRuntimeTest (tenant isolation)
            // === AgentAdminController — gated by PR #969. Service layer IS tenant-scoped via
            //     callerOrgId() but tenant-scoping ≠ admin-only. Class-level @PreAuthorize
            //     ("hasRole('ADMIN')") added in PR #969. 401/403/gate-clear matrix exercised
            //     by AgentAdminAuthzRuntimeTest. cancelRun service-layer cross-tenant orgId
            //     guard pinned separately by AgentAdminServiceTest (PR #972).
            "com.operativus.agentmanager.control.controller.AgentAdminController#getAllAgents",                              // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#getAgent",                                  // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#createAgent",                               // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#updateAgent",                               // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#deleteAgent",                               // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#restoreAgent",                              // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#exportAgent",                               // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#importAgent",                               // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#getAgentTopology",                          // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#getAgentHistory",                           // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#getAgentLogs",                              // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#getAgentAuditHistory",                      // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#getDeveloperMetrics",                       // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#getAgentVersions",                          // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#rollbackAgent",                             // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#bulkAction",                                // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#bulkExport",                                // focused: AgentAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.AgentAdminController#cancelRun"                                  // focused: AgentAdminAuthzRuntimeTest
    );

    @Test
    void everyAdminGatedEndpointMustBeOnTheCoverageManifest() {
        Set<String> live = liveAdminEndpoints();
        Set<String> uncovered = new TreeSet<>(live);
        uncovered.removeAll(ADMIN_ENDPOINT_COVERAGE);

        if (!uncovered.isEmpty()) {
            String report = uncovered.stream().sorted()
                    .collect(Collectors.joining("\n  - ", "  - ", ""));
            fail("""
                    Found %d controller method(s) gated by @PreAuthorize hasRole('ADMIN') (or hasAuthority ROLE_ADMIN)
                    that are NOT on ADMIN_ENDPOINT_COVERAGE. Every admin endpoint MUST be tagged with the
                    test file that exercises its authz contract.

                    Add each to ADMIN_ENDPOINT_COVERAGE with one of:
                      // matrix              — covered by AdminEndpointAuthzRuntimeTest.ADMIN_ENDPOINTS
                      // matrix-super        — covered by AdminEndpointAuthzRuntimeTest.SUPER_ADMIN_ENDPOINTS
                      // focused: <FileName> — covered by a dedicated runtime authz test
                      // TODO: needs focused authz test — admit the gap; ship the test in a follow-on PR

                    Missing:
                    %s""".formatted(uncovered.size(), report));
        }
    }

    @Test
    void coverageManifestDoesNotContainStaleEntries() {
        Set<String> live = liveAdminEndpoints();
        Set<String> stale = new TreeSet<>(ADMIN_ENDPOINT_COVERAGE);
        stale.removeAll(live);
        assertTrue(stale.isEmpty(),
                "ADMIN_ENDPOINT_COVERAGE contains entries that no longer match any admin-gated "
                        + "controller method (method renamed, gate removed, or controller deleted). "
                        + "Remove:\n  - " + String.join("\n  - ", stale));
    }

    // ─── scanning helpers ────────────────────────────────────────────────────

    private static Set<String> liveAdminEndpoints() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        Set<String> hits = new TreeSet<>();
        List<String> classpathErrors = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents(CONTROLLER_BASE_PACKAGE)) {
            String fqcn = bd.getBeanClassName();
            if (fqcn == null) continue;
            Class<?> controllerClass;
            try {
                controllerClass = Class.forName(fqcn);
            } catch (Throwable e) {
                classpathErrors.add(fqcn + ": " + e.getMessage());
                continue;
            }
            PreAuthorize classGate = controllerClass.getAnnotation(PreAuthorize.class);
            boolean classIsAdminGated = classGate != null && ADMIN_GATE.matcher(classGate.value()).find();

            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!hasMappingAnnotation(method)) continue;
                PreAuthorize methodGate = method.getAnnotation(PreAuthorize.class);
                boolean isAdmin = methodGate != null
                        ? ADMIN_GATE.matcher(methodGate.value()).find()
                        : classIsAdminGated;
                if (isAdmin) {
                    hits.add(controllerClass.getName() + "#" + method.getName());
                }
            }
        }
        if (!classpathErrors.isEmpty()) {
            // Surface any class-load issues so the test does not silently under-report.
            throw new IllegalStateException(
                    "Classpath scan errors:\n  - " + String.join("\n  - ", classpathErrors));
        }
        return hits;
    }

    private static boolean hasMappingAnnotation(Method method) {
        for (Class<? extends Annotation> ann : MAPPING_ANNOTATIONS) {
            if (method.isAnnotationPresent(ann)) return true;
        }
        return false;
    }
}
