package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.SandboxCapabilityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SandboxCapabilityRepository extends JpaRepository<SandboxCapabilityEntity, String> {
}
