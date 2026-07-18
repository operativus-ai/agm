package com.operativus.agentmanager.compute.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Regression guard pinning {@code ChatConfig.chatClientBuilder} to
 *   PROTOTYPE scope. Spring AI's {@link org.springframework.ai.chat.client.ChatClient.Builder}
 *   is a stateful, mutable builder ({@code defaultSystem}/{@code defaultAdvisors} mutate the
 *   shared request spec in place). As a singleton, a single shared builder leaked one injector's
 *   configuration into every other — e.g. {@code LlmJudgeScorer.defaultSystem("impartial
 *   evaluator...")} bled into {@code LlmRouteSelector}, corrupting router classification prompts.
 *   Spring AI's own autoconfigured builder bean is prototype-scoped for exactly this reason.
 *   If someone "simplifies" the bean back to singleton, this fails the build immediately.
 * State: Stateless (pure-classpath reflection; no Spring context boot).
 */
class ChatClientBuilderScopeTest {

    @Test
    void chatClientBuilderBeanIsPrototypeScoped() throws NoSuchMethodException {
        Method beanMethod = ChatConfig.class.getDeclaredMethod("chatClientBuilder", List.class);

        // Sanity: it is the builder factory for ChatModel beans (so a signature change can't
        // silently make this assertion vacuous).
        assertEquals(List.class, beanMethod.getParameterTypes()[0]);
        assertEquals("org.springframework.ai.chat.client.ChatClient$Builder",
                beanMethod.getReturnType().getName());

        Scope scope = beanMethod.getAnnotation(Scope.class);
        assertNotNull(scope, "chatClientBuilder MUST be @Scope(prototype) — Spring AI's "
                + "ChatClient.Builder is mutable and a singleton leaks defaultSystem/defaultAdvisors "
                + "across every injector (judge system prompt bled into the router classifier).");
        assertEquals(ConfigurableBeanFactory.SCOPE_PROTOTYPE, scope.value(),
                "chatClientBuilder scope must be 'prototype', not '" + scope.value() + "'.");
    }
}
