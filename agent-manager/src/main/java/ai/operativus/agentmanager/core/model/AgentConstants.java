package ai.operativus.agentmanager.core.model;

/**
 * Standard constants used across the Procurator ecosystem to prevent magic strings
 * for common infrastructure and telemetric tracing primitives.
 */
public final class AgentConstants {

    private AgentConstants() {
        // Prevent instantiation
    }

    /**
     * Standard unknown marker for unresolvable trace IDs or Agent identities.
     */
    public static final String UNKNOWN_TRACE_ID = "unknown";
    
    /**
     * Specific unknown marker for Micrometer traces lacking an active span context.
     */
    public static final String NO_TRACE = "no-trace";

}
