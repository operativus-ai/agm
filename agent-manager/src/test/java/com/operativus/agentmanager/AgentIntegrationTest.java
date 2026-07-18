package com.operativus.agentmanager;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestConstructor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;

@SpringBootTest
@ActiveProfiles("test")
@Disabled("Agents are now database-driven via AgentService.buildChatClient instead of prototype Beans.")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AgentIntegrationTest {

    private final ChatClient financeAgent;

    public AgentIntegrationTest(@Qualifier("financeAgent") ChatClient financeAgent) {
        this.financeAgent = financeAgent;
    }

    @Test
    void testFinanceAgentStockPrice() {
        String response = financeAgent.prompt()
                .user("What is the stock price of NVDA?")
                .call()
                .content();

        System.out.println("Agent Response: " + response);
        
        assertThat(response).contains("150.00");
    }
}
