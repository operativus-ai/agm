package ai.operativus.agentmanager.control.service.erasure;

import ai.operativus.agentmanager.control.repository.AgenticMemoryOutboxRepository;
import ai.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import ai.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class MemoryErasureHandler implements ErasureHandler {

    private final AgenticMemoryRepository memoryRepository;
    private final AgenticMemoryOutboxRepository outboxRepository;
    private final VectorStore vectorStore;

    public MemoryErasureHandler(AgenticMemoryRepository memoryRepository,
                                AgenticMemoryOutboxRepository outboxRepository,
                                VectorStore vectorStore) {
        this.memoryRepository = memoryRepository;
        this.outboxRepository = outboxRepository;
        this.vectorStore = vectorStore;
    }

    @Override
    public String domain() { return "memories"; }

    @Override
    @Transactional
    public int erase(String userId) {
        List<AgenticMemoryEntity> memories = memoryRepository.findByUserId(userId);
        if (memories.isEmpty()) {
            return 0;
        }

        List<String> vectorIds = memories.stream()
                .map(AgenticMemoryEntity::getVectorId)
                .filter(v -> v != null && !v.isBlank())
                .toList();
        if (!vectorIds.isEmpty()) {
            vectorStore.delete(vectorIds);
        }

        List<UUID> memoryIds = memories.stream()
                .map(AgenticMemoryEntity::getMemoryId)
                .toList();
        outboxRepository.deleteByMemoryIdIn(memoryIds);

        memoryRepository.deleteAll(memories);
        return memories.size();
    }
}
