package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.security.CallerContext;
import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.core.model.JobStatusResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

/**
 * Domain Responsibility: User-facing job-status polling surface for the calling tenant.
 * State: Stateless — delegates entirely to {@link BackgroundJobRepository}.
 *
 * <p><b>Authz:</b> {@code isAuthenticated()} gate (not admin-only) — any valid session
 *   may poll its own org's jobs. The admin-only observability surface is
 *   {@code BackgroundJobController} (all orgs).
 *
 * <p><b>Tenant-scoping:</b> both endpoints resolve {@code orgId} from the Security context
 *   and pass it to org-scoped repository queries. Cross-tenant requests return 404 (not 403)
 *   to avoid existence leaks — matches the Schedule / KnowledgeBase / Workflow convention.
 *   System/cron jobs ({@code agentId IS NULL}) are not exposed here.
 */
@RestController
@RequestMapping("/api/jobs")
@PreAuthorize("isAuthenticated()")
public class JobsController {

    private final BackgroundJobRepository jobRepository;

    public JobsController(BackgroundJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> getJob(@PathVariable String jobId) {
        String orgId = Objects.requireNonNull(CallerContext.resolveCallerOrgId());
        return jobRepository.findByIdAndOrg(jobId, orgId)
                .map(job -> ResponseEntity.ok(JobStatusResponse.from(job)))
                .orElse(ResponseEntity.notFound().build());
    }
}
