package com.operativus.agentmanager.compute.service;

import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import com.operativus.agentmanager.core.spi.EnterpriseAdvisorContributor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the edition-advisor seam contract in {@link AgentClientFactory#contributedAdvisors}:
 * with no contributors the chain is untouched (Core behavior byte-identical), contributors are
 * flattened in {@code order()} sequence, and null/empty contributions are skipped. This is the
 * Core-side half of the open-core split's advisor seam (agm-core-oss-execution.md §4.2); the
 * EE repo's contract tests pin the other half.
 */
class AgentClientFactoryContributedAdvisorsTest {

    @Test
    void noContributorsContributesNothing() {
        assertThat(AgentClientFactory.contributedAdvisors(null, def())).isEmpty();
        assertThat(AgentClientFactory.contributedAdvisors(List.of(), def())).isEmpty();
    }

    @Test
    void flattensInOrderSequence() {
        Advisor a1 = namedAdvisor("ee-first");
        Advisor a2 = namedAdvisor("ee-second");
        Advisor a3 = namedAdvisor("ee-third");
        EnterpriseAdvisorContributor late = contributor(10, List.of(a3));
        EnterpriseAdvisorContributor early = contributor(-5, List.of(a1, a2));

        List<Advisor> merged = AgentClientFactory.contributedAdvisors(List.of(late, early), def());

        assertThat(merged).containsExactly(a1, a2, a3);
    }

    @Test
    void skipsNullAndEmptyContributions() {
        Advisor real = namedAdvisor("ee-real");
        EnterpriseAdvisorContributor nullContribution = contributor(0, null);
        EnterpriseAdvisorContributor emptyContribution = contributor(1, List.of());
        EnterpriseAdvisorContributor realContribution = contributor(2, List.of(real));

        List<Advisor> merged = AgentClientFactory.contributedAdvisors(
                List.of(nullContribution, emptyContribution, realContribution), def());

        assertThat(merged).containsExactly(real);
    }

    @Test
    void contributorReceivesTheAgentDefinition() {
        AgentDefinition def = def();
        var seen = new java.util.concurrent.atomic.AtomicReference<AgentDefinition>();
        EnterpriseAdvisorContributor capturing = d -> {
            seen.set(d);
            return List.of();
        };

        AgentClientFactory.contributedAdvisors(List.of(capturing), def);

        assertThat(seen.get()).isSameAs(def);
    }

    private static EnterpriseAdvisorContributor contributor(int order, List<Advisor> advisors) {
        return new EnterpriseAdvisorContributor() {
            @Override
            public List<Advisor> contribute(AgentDefinition def) {
                return advisors;
            }

            @Override
            public int order() {
                return order;
            }
        };
    }

    private static Advisor namedAdvisor(String name) {
        return new CallAdvisor() {
            @Override
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                return chain.nextCall(request);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
    }

    private static AgentDefinition def() {
        return new AgentDefinition(
                "agent-1", "agent-1", "desc", "instr", "gpt-x",
                null, null, null, null,
                false, false, null,
                null, null, false, false, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, com.operativus.agentmanager.core.entity.ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                false, null,
                new HumanReview(true, null, null,
                        OnRejectPolicy.SKIP, null, null, null, null, null), null);
    }
}
