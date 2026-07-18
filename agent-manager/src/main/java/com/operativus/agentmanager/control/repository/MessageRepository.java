package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for the individual messages sent during an Agent conversational Session.
 * State: Stateless
 */
@Repository
public interface MessageRepository extends JpaRepository<AgentMessage, UUID> {
    
    /**
     * @summary Retrieves the message history for a specific session, ordered chronologically by creation time.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<AgentMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
