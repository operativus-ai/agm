package ai.operativus.agentmanager.core.entity;

public enum DefaultModelSlot {
    ROUTER,
    FAST,
    HEAVY,
    EMBEDDING;

    public String toSettingsKey() {
        return "DEFAULT_MODEL_" + this.name();
    }
}
