package com.operativus.agentmanager.compute.tools;

import com.operativus.agentmanager.control.security.RequiresCapability;
import org.springframework.ai.tool.annotation.Tool;


/**
 * Domain Responsibility: Provides additional or alias Spring AI tools for research-focused agents, leveraging underlying KnowledgeTools.
 * State: Stateless
 */
@AgentToolComponent
public class ResearchTools {

    private final KnowledgeTools knowledgeTools;

    public ResearchTools(KnowledgeTools knowledgeTools) {
        this.knowledgeTools = knowledgeTools;
    }

    /**
     * @summary Searches the internal knowledge base for a specified query.
     * @logic Delegates execution directly to the underlying KnowledgeTools component to execute the vector similarity search.
     */
    @RequiresCapability("web_access")
    @Tool(name = "searchKnowledgeBaseTool", description = "Search the knowledge base")
    public String searchKnowledgeBase(String query) {
        return knowledgeTools.searchKnowledgeBase(query);
    }
}
