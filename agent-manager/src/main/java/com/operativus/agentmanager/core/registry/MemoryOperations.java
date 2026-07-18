package com.operativus.agentmanager.core.registry;

import java.util.List;
import java.util.Map;

public interface MemoryOperations {
    void addMemory(String content);
    void addMemory(String content, String userId);
    List<String> searchMemories(String query);
    List<String> searchUserMemories(String userId);
    void deleteMemories(List<String> ids);
    void optimizeMemories(String userId);
    Map<String, Object> getMemoryStats(String userId);
    List<String> getMemoryTopics(String userId);
}
