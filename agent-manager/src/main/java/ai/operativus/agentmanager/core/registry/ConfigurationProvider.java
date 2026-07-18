package ai.operativus.agentmanager.core.registry;

import java.util.List;

public interface ConfigurationProvider {
    int getCrawlerMaxPages(int defaultValue);
    List<String> getCrawlerFormats(List<String> defaultFormats);
}
