package com.operativus.agentmanager.core.registry;

public interface SettingsOperations {
    String getDefaultModelRouter();
    String getDefaultModelFast();
    String getDefaultModelHeavy();
    String getDefaultModelEmbedding();

    int getCompressionThresholdChars(int defaultValue);
    int getSummarizationThresholdTurns(int defaultValue);
}
