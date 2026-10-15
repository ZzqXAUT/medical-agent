package com.example.mednav.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SessionMemoryFinalizerTest {

    private final MemoryService memoryService = mock(MemoryService.class);
    private final MemoryPersistenceService persistence = mock(MemoryPersistenceService.class);
    private final SessionSummaryGenerator summaryGenerator = mock(SessionSummaryGenerator.class);
    private final SessionSummaryFormatter formatter = new SessionSummaryFormatter();
    private final SessionMemoryFinalizer finalizer = new SessionMemoryFinalizer(
            memoryService,
            persistence,
            summaryGenerator,
            formatter,
            new SimpleMeterRegistry());

    @Test
    void deletesWorkingMemoryOnlyAfterTheDatabaseCommitSucceeds() {
        SessionWorkingMemory working = SessionWorkingMemory.empty("session-1", "user-1");
        when(memoryService.working("user-1", "session-1")).thenReturn(working);
        when(summaryGenerator.generate(working)).thenReturn(Mono.just("summary"));
        when(persistence.commit(any(SessionWorkingMemory.class), eq(Instant.EPOCH)))
                .thenReturn(new MemoryCommitResult(false, true, true));
        when(memoryService.refreshUser("user-1"))
                .thenReturn(new UserMemorySnapshot(
                        "user-1", false, Map.of(), List.of(), Map.of(), List.of()));

        StepVerifier.create(finalizer.finalizeSession("user-1", "session-1", Instant.EPOCH))
                .assertNext(result -> {
                    assertThat(result.clinicalPersisted()).isTrue();
                    assertThat(result.summaryPersisted()).isTrue();
                })
                .verifyComplete();

        verify(memoryService).deleteWorking("session-1");
    }

    @Test
    void retainsWorkingMemoryWhenTheDatabaseCommitFails() {
        SessionWorkingMemory working = SessionWorkingMemory.empty("session-2", "user-1");
        when(memoryService.working("user-1", "session-2")).thenReturn(working);
        when(summaryGenerator.generate(working)).thenReturn(Mono.just("summary"));
        when(persistence.commit(any(SessionWorkingMemory.class), eq(Instant.EPOCH)))
                .thenThrow(new IllegalStateException("database unavailable"));

        StepVerifier.create(finalizer.finalizeSession("user-1", "session-2", Instant.EPOCH))
                .expectErrorMessage("database unavailable")
                .verify();

        verify(memoryService, never()).deleteWorking("session-2");
        verify(memoryService, never()).refreshUser("user-1");
    }

    @Test
    void doesNotPersistOrDeleteWhenRedisWorkingMemoryIsUnavailable() {
        when(memoryService.working("user-1", "session-3"))
                .thenThrow(new MemoryCacheUnavailableException(
                        new IllegalStateException("redis unavailable")));

        StepVerifier.create(finalizer.finalizeSession("user-1", "session-3", Instant.EPOCH))
                .expectError(MemoryCacheUnavailableException.class)
                .verify();

        verify(persistence, never()).commit(any(), any());
        verify(memoryService, never()).deleteWorking("session-3");
        verify(memoryService, never()).refreshUser("user-1");
    }
}
