package com.operativus.agentmanager.compute.tools;

import com.operativus.agentmanager.core.model.ScheduleDTO;
import com.operativus.agentmanager.core.registry.ScheduleOperations;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Domain Responsibility: Exposes schedule creation capabilities to Autonomous Agents so they can request delayed processing and continuous persistence.
 * State: Stateless Function
 */
@AgentToolComponent
public class AgentSchedulingTool {

    private final ScheduleOperations scheduleOperations;

    public AgentSchedulingTool(ScheduleOperations scheduleOperations) {
        this.scheduleOperations = scheduleOperations;
    }

    @Tool(description = "Schedules a future autonomous execution using a mathematically valid UNIX cron expression. Allows an agent to delay an action, sleep, or establish a recurring follow-up.")
    public String schedule_task(
            @ToolParam(description = "The UNIX cron expression string.") String cronExpression,
            @ToolParam(description = "The strict instructions the agent should follow when the schedule triggers.") String contextualPrompt,
            @ToolParam(description = "The ID of the target agent to execute.") String targetAgentId,
            @ToolParam(description = "Your active session ID to resume context.") String sessionId) {
        try {
            ScheduleDTO newSchedule = new ScheduleDTO(
                    null,
                    "Autonomous Scheduled Task",
                    "Self-scheduled follow-up by Agent " + targetAgentId,
                    cronExpression,
                    "AGENT",
                    targetAgentId,
                    sessionId,
                    contextualPrompt,
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            
            ScheduleDTO created = scheduleOperations.createSchedule(newSchedule);
            return "SUCCESS: Successfully scheduled execution with ID " + created.id() + ". Cron: " + cronExpression;
        } catch (IllegalArgumentException e) {
            return "FAILED: Invalid CRON expression provided: " + cronExpression + ". Recalculate your cron strictly following standard UNIX cron format.";
        } catch (Exception e) {
            return "FAILED: System error scheduling task: " + e.getMessage();
        }
    }
}
