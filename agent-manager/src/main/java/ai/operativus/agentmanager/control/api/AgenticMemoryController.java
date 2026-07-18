package ai.operativus.agentmanager.control.api;

import ai.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import ai.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Domain Responsibility: REST Governance controller for Agentic Semantic memory. Allows users to view and curate the rules defining agent behavior.
 */
@RestController("agenticMemoryController")
@RequestMapping("/api/v1/memories")
public class AgenticMemoryController {

    private static final Logger log = LoggerFactory.getLogger(AgenticMemoryController.class);

    private final AgenticMemoryRepository memoryRepository;
    private final VectorStore vectorStore;

    public AgenticMemoryController(AgenticMemoryRepository memoryRepository, VectorStore vectorStore) {
        this.memoryRepository = memoryRepository;
        this.vectorStore = vectorStore;
    }

    /**
     * @summary Implements the "Right To Be Forgotten" (RTBF) wipe for a specific user.
     * @logic Locates all semantic memory records associated with the given userId,
     *        deletes their corresponding vector embeddings from the VectorStore using
     *        document-level metadata filtering, and then purges the JPA records.
     *        This approach deletes entire documents rather than attempting to surgically
     *        modify embeddings, which is architecturally infeasible for high-dimensional
     *        vector spaces.
     */
    @DeleteMapping("/rtbf/{userId}")
    @Transactional
    public ResponseEntity<Void> rightToBeForgotten(@PathVariable String userId) {
        log.warn("RTBF Request: Purging ALL memory records for user '{}'", userId);

        // 1. Find all JPA memory records for this user
        List<AgenticMemoryEntity> userMemories = memoryRepository.findByUserId(userId);

        if (userMemories.isEmpty()) {
            log.info("RTBF: No memory records found for user '{}' — user unknown", userId);
            return ResponseEntity.notFound().build();
        }

        // 2. Purge vector embeddings using the stored vectorId links.
        //    This avoids the metadata filter approach, which was using the wrong key ("user_id" vs "userId")
        //    and would silently find 0 documents even when embeddings exist.
        List<String> vectorIds = userMemories.stream()
                .map(AgenticMemoryEntity::getVectorId)
                .filter(id -> id != null && !id.isBlank())
                .toList();

        if (!vectorIds.isEmpty()) {
            try {
                log.info("RTBF: Deleting {} vector embedding(s) for user '{}'", vectorIds.size(), userId);
                vectorStore.delete(vectorIds);
            } catch (Exception e) {
                log.error("RTBF: Failed to purge VectorStore for user '{}': {}", userId, e.getMessage());
                // Continue to purge JPA records even if vector cleanup fails
            }
        }

        // 3. Purge all JPA memory records atomically
        memoryRepository.deleteAll(userMemories);

        // 4. Verify deletion — confirm no ledger records remain
        long remaining = memoryRepository.findByUserId(userId).size();
        if (remaining > 0) {
            log.error("RTBF: Deletion incomplete for user '{}' — {} record(s) still present after deleteAll", userId, remaining);
            return ResponseEntity.internalServerError().build();
        }

        log.info("RTBF: Successfully purged {} memory record(s) for user '{}'", userMemories.size(), userId);
        return ResponseEntity.noContent().build();
    }
}
