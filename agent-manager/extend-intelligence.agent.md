# Extending AgentManager Intelligence

The **AgentManager** is built on **Spring AI** and **Java 21**, designed to be modular and easily extensible. Extending the agent's intelligence involves interacting with three core components: **Tools** (Action), **Advisors** (Reasoning/Behavior), and **Knowledge** (Data).

## 1. Add New "Action" Capabilities (Tools)
Tools allow the agent to interact with the outside world (APIs, Databases, Systems).

**Mechanism:** Spring AI `@Tool` annotation.

### Steps:
1.  **Create a Java Class**: It should be a Spring component (`@Component` or `@Service`).
2.  **Define Methods**: Create public methods for the actions.
3.  **Annotate**:
    *   Use `@Tool` on the method to describe *what* it does.
    *   Use `@ToolParam` on arguments to describe *what inputs* are needed.
4.  **Registration**: Spring AI automatically discovers these beans if `ToolCallAdvisor` is enabled.

### Example:
```java
package ai.operativus.agentmanager.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class SchedulerTools {

    @Tool(description = "Book a meeting on the user's calendar. Returns the booking status.")
    public String bookMeeting(
        @ToolParam(description = "The ISO-8601 date and time for the meeting") String dateTime,
        @ToolParam(description = "The subject or title of the meeting") String subject
    ) {
        // Implementation logic (e.g., call Google Calendar API)
        System.out.printf("Booking '%s' at %s%n", subject, dateTime);
        return "Meeting booked successfully.";
    }
}
```

## 2. Add New "Reasoning" Patterns (Advisors)
Advisors intercept the chat loop to modify the **Prompt** (before sending to LLM) or the **Response** (after receiving from LLM). This is used for safety, validation, logging, or "inner thoughts".

**Mechanism:** `CallAroundAdvisor` interface.

### Steps:
1.  **Implement Interface**: Create a class implementing `org.springframework.ai.chat.client.advisor.CallAroundAdvisor`.
2.  **Override `aroundCall`**:
    *   **Pre-processing**: Inspect or modify `AdvisedRequest` (User input, System Prompt).
    *   **Chain Execution**: Call `chain.nextAroundCall(request)`.
    *   **Post-processing**: Inspect or modify `ChatResponse`.
3.  **Register**: Add the advisor to the `ChatClient.Builder` in `AgentService.java`.

### Example:
```java
package ai.operativus.agentmanager.advisor;

import org.springframework.ai.chat.client.advisor.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.AdvisedRequest;
import org.springframework.ai.chat.model.ChatResponse;

public class SafetyAdvisor implements CallAroundAdvisor {
    @Override
    public ChatResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        // 1. Guardrail: Check input
        if (request.userText().toLowerCase().contains("drop table")) {
            throw new SecurityException("Unsafe SQL detected in prompt.");
        }

        // 2. Proceed with call
        ChatResponse response = chain.nextAroundCall(request);

        // 3. Guardrail: Check output (optional)
        return response;
    }
}
```

### Enable in AgentService:
```java
ChatClient client = chatClientBuilder
    .defaultAdvisors(new SafetyAdvisor())
    .build();
```

## 3. Add Domain Knowledge (RAG)
To make the agent an expert on private data without fine-tuning.

**Mechanism:** `VectorStore` + `QuestionAnswerAdvisor`.

### Steps:
1.  **Ingestion**: Use `KnowledgeService` (which uses Apache Tika) to parse and embed documents into the `PgVectorStore`.
2.  **Retrieval**: The `AgentService` uses `QuestionAnswerAdvisor` to automatically rewrite prompts with relevant context found in the vector DB.

### Configuration (`AgentService.java`):
```java
// Ensure this is uncommented in AgentService.java
.defaultAdvisors(new QuestionAnswerAdvisor(vectorStore, SearchRequest.builder().build())) 
```

## 4. Define New Agent Personas
To create specialized agents (e.g., "DevOps Bot" vs. "Support Agent").

**Mechanism:** `AgentRegistry` and `AgentDefinition`.

### Steps:
1.  Open `AgentRegistry.java`.
2.  Add a new entry to the `agents` map.

### Example:
```java
agents.put("devops-agent", new AgentDefinition(
    "devops-agent",
    "gpt-4-turbo",
    """
    You are a DevOps expert. 
    You have access to kubectl and aws-cli tools.
    Always confirm before executing destructive commands.
    """
));
```

## Summary Checklist

| Goal | Component | Key Annotation/Interface | Location |
| :--- | :--- | :--- | :--- |
| **New Action** | Tools | `@Tool` | `src/.../tools/` |
| **New Behavior** | Advisors | `CallAroundAdvisor` | `src/.../advisor/` |
| **New Data** | Knowledge | `VectorStore` | `src/.../service/KnowledgeService.java` |
| **New Role** | Persona | `AgentDefinition` | `src/.../repository/definitions/AgentRegistry.java` |
