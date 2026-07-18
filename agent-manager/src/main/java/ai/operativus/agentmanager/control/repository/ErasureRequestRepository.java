package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.ErasureRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ErasureRequestRepository extends JpaRepository<ErasureRequest, UUID> {

    List<ErasureRequest> findByUserIdOrderByRequestedAtDesc(String userId);

    /** Bug #50 — tenant-scoped read. Used by the compliance controller's list path;
     *  the unscoped finder above stays for system / admin-tooling callers. */
    List<ErasureRequest> findByUserIdAndOrgIdOrderByRequestedAtDesc(String userId, String orgId);

    List<ErasureRequest> findByStatus(ErasureRequest.Status status);
}
