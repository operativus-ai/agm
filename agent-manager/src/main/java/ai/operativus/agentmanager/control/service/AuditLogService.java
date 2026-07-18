package ai.operativus.agentmanager.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.repository.AgentAuditRepository;
import ai.operativus.agentmanager.control.service.audit.ChangesetScrubber;
import ai.operativus.agentmanager.core.entity.AgentAuditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain Responsibility: Paginated, filtered retrieval of audit log entries for the admin dashboard.
 * State: Stateless
 */
@Service
public class AuditLogService {

    /** Hard cap on rows in a single CSV export response, applied at the service layer
     *  rather than through the global Pageable resolver — bypasses the project-wide
     *  MAX_PAGE_SIZE=200 clamp from {@code PaginationDefaultsConfig}. The cap protects
     *  the admin endpoint against accidental full-table dumps that would pin a
     *  request thread for seconds. Operators wanting more should slice by filter. */
    static final int MAX_EXPORT_ROWS = 10_000;

    private final AgentAuditRepository auditRepository;
    private final ChangesetScrubber changesetScrubber;

    public AuditLogService(AgentAuditRepository auditRepository, ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.changesetScrubber = new ChangesetScrubber(objectMapper);
    }

    /**
     * @summary Paginated, filtered view of {@code agent_audits} for the admin dashboard.
     *     Returns entities with {@code changeset} JSON passed through {@link ChangesetScrubber}
     *     so secret-key values (api_key, password, token, etc.) are masked before reaching
     *     the JSON response.
     * @logic {@code @Transactional(readOnly = true)} ensures the post-read mutation of
     *     {@code changeset} on each row is NOT flushed back to the database — Spring sets
     *     Hibernate flush mode to MANUAL/NEVER for read-only transactions.
     */
    @Transactional(readOnly = true)
    public Page<AgentAuditEntity> listAuditLogs(String orgId, String username, String action, String agentId, Pageable pageable) {
        return listAuditLogs(orgId, username, action, agentId, null, null, pageable);
    }

    /**
     * @summary Date-range-aware list. {@code createdAtFrom} is inclusive; {@code createdAtTo}
     *     is exclusive. Either bound may be null. Compliance queries ("show me all audit
     *     activity for user X between Dec 1 and Dec 15") need this surface; pre-existing
     *     callers without date-range needs use the no-arg overload above.
     */
    /**
     * Sentinel bounds for the {@code (createdAt >= :from AND createdAt < :to)} predicate
     * when no explicit range is given. {@code LocalDateTime.MIN}/{@code MAX} are outside
     * PG's timestamp range (it caps at year 294276); these year-1/year-9999 bounds keep
     * the planner happy without an artificial upper-bound truncation in practice.
     */
    static final LocalDateTime EPOCH_MIN = LocalDateTime.of(1, 1, 1, 0, 0, 0);
    static final LocalDateTime EPOCH_MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    public Page<AgentAuditEntity> listAuditLogs(String orgId, String username, String action, String agentId,
                                                 LocalDateTime createdAtFrom, LocalDateTime createdAtTo,
                                                 Pageable pageable) {
        Objects.requireNonNull(orgId, "orgId must not be null — verify resolveCallerOrgId succeeded");
        LocalDateTime fromOrMin = createdAtFrom == null ? EPOCH_MIN : createdAtFrom;
        LocalDateTime toOrMax = createdAtTo == null ? EPOCH_MAX : createdAtTo;
        return auditRepository.search(orgId, blankToNull(agentId), blankToNull(username), blankToNull(action),
                        fromOrMin, toOrMax, pageable)
                .map(row -> {
                    row.setChangeset(changesetScrubber.scrub(row.getChangeset()));
                    return row;
                });
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * @summary Exports filtered agent_audits rows as RFC-4180 CSV (newest first), capped
     *     at {@link #MAX_EXPORT_ROWS}. Each row's {@code changeset} JSON is scrubbed via
     *     {@link ChangesetScrubber} so known secret-key values (api_key, password, token,
     *     private_key, etc.) never reach an exported file.
     * @logic Reuses {@link #listAuditLogs} with a service-internal PageRequest so the
     *     global Pageable resolver's max-page-size clamp doesn't apply. The scrubber's
     *     default secret-key set covers the platform's expected fields (T018); custom
     *     deployments can override by injecting a different ChangesetScrubber instance.
     */
    public String exportAsCsv(String orgId, String username, String action, String agentId) {
        return exportAsCsv(orgId, username, action, agentId, null, null);
    }

    public String exportAsCsv(String orgId, String username, String action, String agentId,
                              LocalDateTime createdAtFrom, LocalDateTime createdAtTo) {
        Pageable pageable = PageRequest.of(0, MAX_EXPORT_ROWS,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AgentAuditEntity> page = listAuditLogs(orgId, username, action, agentId,
                createdAtFrom, createdAtTo, pageable);

        StringBuilder sb = new StringBuilder(64 * page.getNumberOfElements());
        sb.append("createdAt,id,agentId,action,username,versionNumber,changeset\r\n");
        for (AgentAuditEntity row : page.getContent()) {
            sb.append(csvEscape(row.getCreatedAt() == null ? "" : row.getCreatedAt().toString())).append(',');
            sb.append(csvEscape(row.getId())).append(',');
            sb.append(csvEscape(row.getAgentId())).append(',');
            sb.append(csvEscape(row.getAction())).append(',');
            sb.append(csvEscape(row.getUsername())).append(',');
            sb.append(row.getVersionNumber() == null ? "" : row.getVersionNumber()).append(',');
            sb.append(csvEscape(changesetScrubber.scrub(row.getChangeset()))).append("\r\n");
        }
        return sb.toString();
    }

    /** Minimal RFC-4180 escaper. Quotes any field containing comma, quote, CR, or LF;
     *  doubles internal quotes. Null becomes the empty string. */
    private static String csvEscape(String s) {
        if (s == null) return "";
        boolean needsQuoting = s.indexOf(',') >= 0
                || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0;
        if (!needsQuoting) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
