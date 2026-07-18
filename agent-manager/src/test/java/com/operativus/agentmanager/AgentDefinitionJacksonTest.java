package com.operativus.agentmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class AgentDefinitionJacksonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testToolsDeserialization() throws Exception {
        String jsonPayload = """
        {
            "agentId": "test-id",
            "name": "Test Agent",
            "description": "desc",
            "instructions": "inst",
            "model": "gpt-4",
            "tools": ["rag", "search"]
        }
        """;

        AgentDefinition dto = objectMapper.readValue(jsonPayload, AgentDefinition.class);
        
        System.out.println("Mapped DTO Tools: " + dto.tools());

        assertThat(dto.id()).isEqualTo("test-id");
        assertThat(dto.tools()).isNotNull();
        assertThat(dto.tools()).containsExactly("rag", "search");
        
        String outputJson = objectMapper.writeValueAsString(dto);
        System.out.println("Serialized Output: " + outputJson);
        assertThat(outputJson).contains("\"tools\":[\"rag\",\"search\"]");
    }
}
