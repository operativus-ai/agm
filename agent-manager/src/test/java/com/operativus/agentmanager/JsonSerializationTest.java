package com.operativus.agentmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.operativus.agentmanager.core.model.ModelDTO;
import com.operativus.agentmanager.core.entity.ModelType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

public class JsonSerializationTest {

    @Test
    public void testSerialization() throws Exception {
        ModelDTO dto = new ModelDTO(
                "gemini-2.5-pro",
                "Gemini 2.5 Pro",
                "GOOGLE",
                null,
                "gemini-2.5-pro",
                true,
                true,
                true,
                2000000,
                8192,
                null,
                ModelType.CHAT,
                LocalDateTime.now(),
                LocalDateTime.now(),
                0L,
                true,
                LocalDateTime.now(),
                0L,
                null,
                false
        );

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        
        System.out.println("---- BEGIN JSON ----");
        System.out.println(mapper.writeValueAsString(dto));
        System.out.println("---- END JSON ----");
    }
}
