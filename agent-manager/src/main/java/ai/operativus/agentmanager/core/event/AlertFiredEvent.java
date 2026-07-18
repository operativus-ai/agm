package ai.operativus.agentmanager.core.event;

import org.springframework.context.ApplicationEvent;

public class AlertFiredEvent extends ApplicationEvent {

    private final String ruleId;
    private final String eventId;
    private final String severity;
    private final String message;
    /** Tenant that owns this alert; used by AlertIntegrationService to scope dispatch. */
    private final String orgId;

    public AlertFiredEvent(Object source, String ruleId, String eventId, String severity, String message, String orgId) {
        super(source);
        this.ruleId = ruleId;
        this.eventId = eventId;
        this.severity = severity;
        this.message = message;
        this.orgId = orgId;
    }

    public String getRuleId() { return ruleId; }
    public String getEventId() { return eventId; }
    public String getSeverity() { return severity; }
    public String getMessage() { return message; }
    public String getOrgId() { return orgId; }
}
