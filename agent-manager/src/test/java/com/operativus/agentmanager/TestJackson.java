package com.operativus.agentmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;

public class TestJackson {
    public static void main(String[] args) throws Exception {
        String json = "{\"agentId\":\"123\",\"name\":\"Test\",\"description\":\"D\",\"instructions\":\"I\",\"model\":\"gpt-4o\",\"tools\":[\"search_knowledge_base\"]}";
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new ParameterNamesModule()); // Spring boot registers this by default
        try {
            AgentDefinition def = mapper.readValue(json, AgentDefinition.class);
            System.out.println("Parsed tools: " + def.tools());
            System.out.println("Reserialized: " + mapper.writeValueAsString(def));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
