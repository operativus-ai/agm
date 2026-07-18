package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.FinOpsValuationRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FinOpsValuationRateRepository extends JpaRepository<FinOpsValuationRateEntity, String> {
}
