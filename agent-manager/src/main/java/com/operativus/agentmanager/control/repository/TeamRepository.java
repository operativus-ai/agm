package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.Team;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Orchestrated Multi-Agent Team definitions.
 * State: Stateless
 */
@Repository
public interface TeamRepository extends JpaRepository<Team, String> {

    // ── Org-scoped finders (tenant isolation) ───────────────────

    Page<Team> findAllByOrgId(String orgId, Pageable pageable);

    java.util.Optional<Team> findByIdAndOrgId(String id, String orgId);

    boolean existsByIdAndOrgId(String id, String orgId);

    Page<Team> findByArchivedAndOrgId(Boolean archived, String orgId, Pageable pageable);

    @Query("SELECT t FROM Team t WHERE t.orgId = :orgId AND t.archived = :archived AND " +
           "(LOWER(t.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(t.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Team> findByArchivedAndOrgIdAndSearch(@Param("archived") Boolean archived,
                                               @Param("orgId") String orgId,
                                               @Param("search") String search,
                                               Pageable pageable);

    @Query("SELECT t FROM Team t WHERE t.orgId = :orgId AND " +
           "(LOWER(t.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(t.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Team> findByOrgIdAndSearch(@Param("orgId") String orgId,
                                    @Param("search") String search,
                                    Pageable pageable);

    // ── Legacy unscoped (retained for internal tooling only) ────

    /**
     * Paginated teams filtered by archived status.
     */
    Page<Team> findByArchived(Boolean archived, Pageable pageable);

    /**
     * Paginated teams filtered by archived status with name/description search.
     */
    @Query("SELECT t FROM Team t WHERE t.archived = :archived AND " +
           "(LOWER(t.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(t.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Team> findByArchivedAndSearch(@Param("archived") Boolean archived,
                                       @Param("search") String search,
                                       Pageable pageable);

    /**
     * Paginated search across all teams (both archived and active).
     */
    @Query("SELECT t FROM Team t WHERE " +
           "LOWER(t.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(t.description) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Team> findBySearch(@Param("search") String search, Pageable pageable);

    List<Team> findByOrgId(String orgId);
}
