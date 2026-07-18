package ai.operativus.agentmanager.core.model;

import java.util.Map;
import java.util.List;

public record TraceSpanDTO(
    String id,
    String parentId,
    String name,
    String startTime, // ISO String
    String endTime, // ISO String
    long durationMs,
    Map<String, String> attributes,
    List<TraceSpanDTO> children
) {}
