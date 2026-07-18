package ai.operativus.agentmanager.control.service.erasure;

import ai.operativus.agentmanager.control.repository.KnowledgeBaseRepository;
import ai.operativus.agentmanager.control.repository.KnowledgeContentRepository;
import ai.operativus.agentmanager.control.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class KnowledgeErasureHandler implements ErasureHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeErasureHandler.class);

    private final KnowledgeContentRepository contentRepository;
    private final KnowledgeBaseRepository baseRepository;
    private final KnowledgeService knowledgeService;

    public KnowledgeErasureHandler(KnowledgeContentRepository contentRepository,
                                   KnowledgeBaseRepository baseRepository,
                                   KnowledgeService knowledgeService) {
        this.contentRepository = contentRepository;
        this.baseRepository = baseRepository;
        this.knowledgeService = knowledgeService;
    }

    @Override
    public String domain() { return "knowledge"; }

    @Override
    @Transactional
    public int erase(String userId) {
        int deleted = 0;

        var documents = contentRepository.findByOwnerId(userId);
        for (var doc : documents) {
            if (contentRepository.existsById(doc.getId())) {
                knowledgeService.delete(doc.getId());
                deleted++;
            } else {
                log.debug("Knowledge document {} already deleted — skipping", doc.getId());
            }
        }

        var bases = baseRepository.findByOwnerId(userId);
        for (var kb : bases) {
            // Only delete a base if all its content was owned by this user (no shared content remains).
            long remaining = contentRepository.countByKnowledgeBaseId(kb.getId());
            if (remaining == 0) {
                baseRepository.delete(kb);
                deleted++;
                log.info("Deleted now-empty knowledge base {} owned by {}", kb.getId(), userId);
            } else {
                log.info("Knowledge base {} still has {} document(s) from other owners; skipping base deletion", kb.getId(), remaining);
            }
        }

        return deleted;
    }
}
