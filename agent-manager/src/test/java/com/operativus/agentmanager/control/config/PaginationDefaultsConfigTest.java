package com.operativus.agentmanager.control.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the static {@link PaginationDefaultsConfig#clampedPageRequest(int, int)}
 * helper used by controllers that build a {@code PageRequest} manually from raw {@code @RequestParam}
 * values (RunsController, BackgroundJobController, WorkflowsController.getWorkflowRuns) — those bypass
 * the resolver bean tested separately by {@code SystemAuditRuntimeTest.paginatedAdminListClampsAtGlobalMaxPageSize}.
 * State: Stateless unit test.
 */
class PaginationDefaultsConfigTest {

    @Test
    void belowCapIsHonoredAsIs() {
        PageRequest req = PaginationDefaultsConfig.clampedPageRequest(0, 50);
        assertEquals(50, req.getPageSize());
        assertEquals(0, req.getPageNumber());
    }

    @Test
    void atCapIsHonoredAsIs() {
        PageRequest req = PaginationDefaultsConfig.clampedPageRequest(0, PaginationDefaultsConfig.MAX_PAGE_SIZE);
        assertEquals(PaginationDefaultsConfig.MAX_PAGE_SIZE, req.getPageSize());
    }

    @Test
    void aboveCapIsClampedToMax() {
        PageRequest req = PaginationDefaultsConfig.clampedPageRequest(3, 10_000);
        assertEquals(PaginationDefaultsConfig.MAX_PAGE_SIZE, req.getPageSize());
        assertEquals(3, req.getPageNumber(), "page number is not affected by the size clamp");
    }
}
