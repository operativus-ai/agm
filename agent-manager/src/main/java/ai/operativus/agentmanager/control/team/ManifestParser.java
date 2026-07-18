package ai.operativus.agentmanager.control.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import ai.operativus.agentmanager.core.model.TeamManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ManifestParser {

    private static final Logger log = LoggerFactory.getLogger(ManifestParser.class);
    private final ObjectMapper yamlMapper;
    
    @Value("${agentmanager.teams.manifest-path:classpath:teams.yaml}")
    private Resource manifestResource;

    private final Map<String, TeamManifest> parsedManifests = new ConcurrentHashMap<>();

    public ManifestParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @PostConstruct
    public void init() {
        loadManifests();
    }

    public void loadManifests() {
        log.info("Loading team manifests from YAML configuration");
        try {
            if (!manifestResource.exists()) {
                log.warn("Team manifest file not found at {}. Using default empty manifests.", manifestResource.getDescription());
                return;
            }
            try (InputStream is = manifestResource.getInputStream()) {
                List<TeamManifest> manifests = yamlMapper.readValue(is, new TypeReference<List<TeamManifest>>() {});
                manifests.forEach(m -> parsedManifests.put(m.teamId(), m));
                log.info("Successfully loaded {} team manifests.", parsedManifests.size());
            }
        } catch (IOException e) {
            log.error("Failed to parse Team Manifest YAML. FinOps defaults will be zero-trusted.", e);
        } catch (NoClassDefFoundError e) {
            log.error("Jackson YAML dependency is missing.", e);
        }
    }

    public TeamManifest getManifestForTeam(String teamId) {
        return parsedManifests.get(teamId);
    }
}
