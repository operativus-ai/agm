package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.core.model.ToolCategory;
import com.operativus.agentmanager.core.registry.ToolRegistry;
import com.operativus.agentmanager.control.security.RequiresCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Requirement 3.5.1: System Configuration API
 * Domain Responsibility: Exposes dynamically registered tools (including standard and external MCP tools) to the frontend UI.
 * Enriches each tool with its resolved {@link ToolCategory} based on {@code @RequiresCapability} annotations.
 * State: Stateless
 * Dependencies: AgentClientFactory
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private static final Logger log = LoggerFactory.getLogger(ToolController.class);
    private final ToolRegistry agentClientFactory;
    private final ApplicationContext applicationContext;
    // Built lazily on first request, NOT in the constructor. buildToolCategoryMap walks
    // every bean via ctx.getBean(name), force-instantiating the whole context during THIS
    // controller's construction. Depending on bean-creation order that can run while MVC
    // infrastructure (e.g. viewResolver) is still mid-creation, raising
    // BeanCurrentlyInCreationException and failing context load (observed when
    // agm.skills.enabled=false reorders beans). Deferring to first use runs the scan after
    // the context is fully initialized, where each ctx.getBean(name) just returns a cached
    // singleton — no ordering hazard.
    private volatile Map<String, ToolCategory> toolCategoryMap;

    public ToolController(ToolRegistry agentClientFactory,
                          ApplicationContext applicationContext) {
        this.agentClientFactory = agentClientFactory;
        this.applicationContext = applicationContext;
    }

    private Map<String, ToolCategory> toolCategoryMap() {
        Map<String, ToolCategory> m = toolCategoryMap;
        if (m == null) {
            synchronized (this) {
                m = toolCategoryMap;
                if (m == null) {
                    m = buildToolCategoryMap(applicationContext);
                    toolCategoryMap = m;
                }
            }
        }
        return m;
    }

    @GetMapping
    public List<ToolItem> getTools() {
        log.debug("API request to /api/tools");
        Map<String, ToolCategory> categories = toolCategoryMap();
        return agentClientFactory.getAvailableTools().values().stream()
                .map(tc -> {
                    String name = tc.getToolDefinition().name();
                    ToolCategory category = categories.get(name);
                    return new ToolItem(name, formatLabel(name), tc.getToolDefinition().description(),
                            category != null ? category.name() : null,
                            category != null ? category.getDisplayName() : "General");
                })
                .toList();
    }

    /**
     * Scans all Spring beans for @RequiresCapability annotations on @Tool methods
     * and maps tool names to their ToolCategory.
     */
    private Map<String, ToolCategory> buildToolCategoryMap(ApplicationContext ctx) {
        Map<String, ToolCategory> map = new HashMap<>();
        for (String beanName : ctx.getBeanDefinitionNames()) {
            try {
                Object bean = ctx.getBean(beanName);
                for (Method method : bean.getClass().getDeclaredMethods()) {
                    RequiresCapability rc = method.getAnnotation(RequiresCapability.class);
                    org.springframework.ai.tool.annotation.Tool toolAnnotation = method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                    if (rc != null && toolAnnotation != null) {
                        String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                        ToolCategory category = ToolCategory.fromCapability(rc.value());
                        if (category != null) {
                            map.put(toolName, category);
                        }
                    }
                }
            } catch (Exception e) {
                // Skip beans that can't be introspected (proxies, etc.)
            }
        }
        log.info("Built tool category map: {} tools categorized", map.size());
        return map;
    }

    private String formatLabel(String name) {
        if (name == null || name.isEmpty()) return name;
        String replaced = name.replace("_", " ").replace("-", " ");
        String[] words = replaced.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                sb.append(word.substring(1).toLowerCase());
                sb.append(" ");
            }
        }
        return sb.toString().trim();
    }

    public record ToolItem(String id, String label, String desc, String category, String categoryLabel) {}
}
