package com.operativus.agentmanager.compute.tools;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

/**
 * Domain Responsibility: Provides a singleton WebClient bean for the E2B remote sandbox tool with
 * provider-specific timeouts (10s connect / 60s read) and a 1 MB in-memory cap to match the
 * tool's output size cap and avoid Spring's default 256 KB DataBufferLimitException on large
 * but legitimate outputs.
 * State: Stateless. Bean is a singleton.
 */
@Configuration
public class E2BWebClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final int MAX_IN_MEMORY_BYTES = 1024 * 1024; // 1 MB — matches E2BSandboxTool output cap

    @Bean(name = "e2bWebClient")
    public WebClient e2bWebClient(@Value("${agent.tools.e2b.base-url:https://api.e2b.dev}") String baseUrl) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
