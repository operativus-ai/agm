package ai.operativus.agentmanager.compute.memory;

import ai.operativus.agentmanager.control.repository.AgenticMemoryOutboxRepository;
import ai.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import ai.operativus.agentmanager.core.entity.AgenticMemoryOutboxEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pins the single-flight contract on
 * {@link MemoryConsolidationWorker#consolidateNow()} — when a consolidation pass is already
 * in flight (typically the cron-driven loop), a concurrent manual trigger from the REST
 * admin endpoint must return {@code -1} immediately rather than queuing a second
 * concurrent pass through the outbox.
 *
 * <p>Pure JUnit + Mockito — no Spring context, no Testcontainers. We mock the outbox
 * repository to block on a {@link CountDownLatch} so we can deterministically force two
 * threads into the {@code consolidateNow()} body simultaneously.
 *
 * State: Stateless (each test instantiates a fresh worker).
 */
class MemoryConsolidationWorkerSingleFlightTest {

    private AgenticMemoryOutboxRepository outboxRepo;
    private AgenticMemoryRepository memoryRepo;
    private VectorStore vectorStore;
    private ChatClient.Builder chatClientBuilder;
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private MemoryConsolidationWorker worker;

    @BeforeEach
    void setUp() {
        outboxRepo = mock(AgenticMemoryOutboxRepository.class);
        memoryRepo = mock(AgenticMemoryRepository.class);
        vectorStore = mock(VectorStore.class);
        ChatClient mockClient = mock(ChatClient.class);
        chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.build()).thenReturn(mockClient);
        jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        worker = new MemoryConsolidationWorker(outboxRepo, memoryRepo, vectorStore, chatClientBuilder, jdbcTemplate);
    }

    @Test
    void consolidateNowReturnsZeroWhenOutboxIsEmpty() {
        when(outboxRepo.findPendingEventsAndLock(anyInt())).thenReturn(List.of());

        int result = worker.consolidateNow();

        assertThat(result)
                .as("Empty outbox produces zero processed events, not the in-flight sentinel")
                .isEqualTo(0);
    }

    @Test
    void consolidateNowReturnsMinusOneWhenAnotherPassIsInFlight() throws Exception {
        // Block the first call inside findPendingEventsAndLock so the second call lands while
        // the first holds the consolidationInFlight guard. Without the guard, both calls would
        // proceed through the outbox simultaneously — introducing the double-pass bug §3.2.2 forbids.
        CountDownLatch firstCallEntered = new CountDownLatch(1);
        CountDownLatch firstCallMayProceed = new CountDownLatch(1);
        AtomicInteger firstCallInvocations = new AtomicInteger(0);

        when(outboxRepo.findPendingEventsAndLock(anyInt())).thenAnswer(invocation -> {
            int callNumber = firstCallInvocations.incrementAndGet();
            if (callNumber == 1) {
                firstCallEntered.countDown();
                firstCallMayProceed.await(5, TimeUnit.SECONDS);
            }
            return List.<AgenticMemoryOutboxEntity>of();
        });

        CompletableFuture<Integer> firstCall = CompletableFuture.supplyAsync(worker::consolidateNow);

        // Wait for the first call to be inside consolidateNow (past the CAS, blocking on the
        // outbox repo). At this point the in-flight guard is set.
        assertThat(firstCallEntered.await(5, TimeUnit.SECONDS))
                .as("First consolidateNow() call must enter the body within 5s")
                .isTrue();

        int secondCallResult = worker.consolidateNow();
        assertThat(secondCallResult)
                .as("Second concurrent consolidateNow() must return -1 (alreadyRunning sentinel)")
                .isEqualTo(-1);

        // Allow the first call to complete and verify it processed normally.
        firstCallMayProceed.countDown();
        Integer firstCallResult = firstCall.get(5, TimeUnit.SECONDS);
        assertThat(firstCallResult)
                .as("First call should have processed zero events (empty outbox), not -1")
                .isEqualTo(0);
    }

    @Test
    void inFlightGuardReleasesAfterCompletionSoSubsequentCallsCanProceed() {
        when(outboxRepo.findPendingEventsAndLock(anyInt())).thenReturn(List.of());

        int first = worker.consolidateNow();
        int second = worker.consolidateNow();

        assertThat(first)
                .as("First call processes empty outbox normally")
                .isEqualTo(0);
        assertThat(second)
                .as("Subsequent call after first completes must NOT see the guard set")
                .isEqualTo(0);
    }
}
