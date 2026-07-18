package ai.operativus.agentmanager.core.spi;

import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;

import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;

/**
 * Service Provider Interface (SPI) for edition add-on advisors.
 *
 * <p>{@code AgentClientFactory} autowires a {@code List<EnterpriseAdvisorContributor>} of all
 * registered contributors (empty when none exist — Core ships no implementation) and merges
 * each contributor's advisors into the per-request chain <strong>after the static safety
 * chain</strong> (agent-id injection, logging, cache, prompt-injection, PII, content safety,
 * metrics) and <strong>before the per-agent conditional advisors</strong> (extension hooks,
 * structured-output retry, memory, RAG).</p>
 *
 * <p>Contributed advisors therefore always observe PII-redacted prompts (they run at or after
 * the order-10 PII boundary — see {@code AdvisorPiiBoundaryContractTest}) and cannot displace
 * any Core safety advisor. Implementations are discovered via normal Spring component scanning
 * or auto-configuration from an add-on artifact (e.g. agm-enterprise); Core requires no
 * knowledge of them.</p>
 *
 * <p>This SPI exists so an edition artifact can join the advisor chain without Core importing
 * edition code — the chain is otherwise assembled from constructor-injected Core advisors
 * only. Per docs/plans/agm-core-oss-execution.md §4.2.</p>
 */
public interface EnterpriseAdvisorContributor {

    /**
     * Advisors to append to the chain for this agent, or an empty list to contribute nothing
     * for this request. Called once per {@code ChatClient} build; implementations must be
     * cheap and side-effect-free.
     *
     * @param def the resolved definition of the agent whose client is being built
     */
    List<Advisor> contribute(AgentDefinition def);

    /**
     * Relative ordering among contributors (lower runs earlier). Contributors are sorted by
     * this value before their advisors are appended.
     */
    default int order() {
        return 0;
    }
}
