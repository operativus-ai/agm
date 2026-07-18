package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.compute.tools.composio.ComposioActionRegistry;
import com.operativus.agentmanager.control.dto.composio.ConfigDriftResponse;
import com.operativus.agentmanager.control.dto.composio.ConfigDriftResponse.ActionDrift;
import com.operativus.agentmanager.control.dto.composio.ConfigDriftResponse.ConnectionRow;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.ComposioActionConfigRepository;
import com.operativus.agentmanager.control.repository.ComposioConnectionConfigRepository;
import com.operativus.agentmanager.core.entity.ComposioActionConfig;
import com.operativus.agentmanager.core.entity.ComposioConnectionConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Aggregates live registry state, DB action configs, and DB connection
 * configs into a single config-drift snapshot for SUPER_ADMIN observability.
 * State: Stateless — reads from repository and registry on each call.
 */
@Service
public class ComposioConfigDriftService {

    private final ComposioActionConfigRepository actionRepo;
    private final ComposioConnectionConfigRepository connectionRepo;
    private final AgentRepository agentRepo;
    private final ComposioActionRegistry actionRegistry;

    public ComposioConfigDriftService(
            ComposioActionConfigRepository actionRepo,
            ComposioConnectionConfigRepository connectionRepo,
            AgentRepository agentRepo,
            ComposioActionRegistry actionRegistry) {
        this.actionRepo = actionRepo;
        this.connectionRepo = connectionRepo;
        this.agentRepo = agentRepo;
        this.actionRegistry = actionRegistry;
    }

    @Transactional(readOnly = true)
    public ConfigDriftResponse buildDriftSnapshot() {
        // ── Action drift ──────────────────────────────────────────────────
        List<ComposioActionConfig> allDbActions = actionRepo.findAll();
        Set<String> dbEnabledNames = allDbActions.stream()
                .filter(ComposioActionConfig::isEnabled)
                .map(a -> a.getActionName().toUpperCase())
                .collect(Collectors.toSet());
        Set<String> dbAllNames = allDbActions.stream()
                .map(a -> a.getActionName().toUpperCase())
                .collect(Collectors.toSet());
        List<String> dbDisabled = allDbActions.stream()
                .filter(a -> !a.isEnabled())
                .map(ComposioActionConfig::getActionName)
                .sorted()
                .toList();

        Set<String> liveRegistry = actionRegistry.getEnabledActions();
        String registrySource = dbEnabledNames.isEmpty() ? "PROPERTIES_FALLBACK" : "DB";

        List<String> inRegistryNotInDb = liveRegistry.stream()
                .filter(name -> !dbAllNames.contains(name))
                .sorted()
                .toList();
        List<String> inSync = liveRegistry.stream()
                .filter(dbEnabledNames::contains)
                .sorted()
                .toList();

        ActionDrift actionDrift = new ActionDrift(
                allDbActions.size(),
                dbEnabledNames.size(),
                liveRegistry.size(),
                inRegistryNotInDb,
                dbDisabled,
                inSync
        );

        // ── Connection coverage ───────────────────────────────────────────
        List<ComposioConnectionConfig> connections = connectionRepo.findAll();
        Set<String> orgsWithConnection = connections.stream()
                .map(ComposioConnectionConfig::getOrgId)
                .collect(Collectors.toSet());

        List<ConnectionRow> connectionRows = connections.stream()
                .map(c -> new ConnectionRow(c.getOrgId(), c.getConnectionId(), c.getUpdatedAt()))
                .sorted(java.util.Comparator.comparing(ConnectionRow::orgId))
                .toList();

        List<String> orgsWithoutConnection = agentRepo.findDistinctOrgIds().stream()
                .filter(orgId -> !orgsWithConnection.contains(orgId))
                .sorted()
                .toList();

        return new ConfigDriftResponse(
                Instant.now(),
                registrySource,
                actionRegistry.wasTruncated(),
                actionDrift,
                connectionRows,
                orgsWithoutConnection
        );
    }
}
