package com.example.mednav.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SessionRunSchedulerTest {

    private final SessionRunScheduler scheduler = new SessionRunScheduler();

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @RepeatedTest(25)
    void serializesRunsWithinOneSession() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        List<Integer> starts = new CopyOnWriteArrayList<>();

        Flux<Integer> results = Flux.merge(
                scheduled("session-a", 1, active, maxActive, starts),
                scheduled("session-a", 2, active, maxActive, starts),
                scheduled("session-a", 3, active, maxActive, starts));

        StepVerifier.create(results.collectList())
                .assertNext(values -> assertThat(values).containsExactlyInAnyOrder(1, 2, 3))
                .verifyComplete();

        assertThat(maxActive.get()).isEqualTo(1);
        assertThat(starts).containsExactly(1, 2, 3);
    }

    @Test
    void allowsDifferentSessionsToRunConcurrently() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        List<Integer> starts = new CopyOnWriteArrayList<>();

        Flux<Integer> results = Flux.merge(
                scheduled("session-a", 1, active, maxActive, starts),
                scheduled("session-b", 2, active, maxActive, starts));

        StepVerifier.create(results.collectList())
                .assertNext(values -> assertThat(values).containsExactlyInAnyOrder(1, 2))
                .verifyComplete();

        assertThat(maxActive.get()).isEqualTo(2);
    }

    private Mono<Integer> scheduled(
            String sessionId,
            int value,
            AtomicInteger active,
            AtomicInteger maxActive,
            List<Integer> starts) {
        return scheduler.schedule(sessionId, () -> Mono.defer(() -> {
            starts.add(value);
            int nowActive = active.incrementAndGet();
            maxActive.accumulateAndGet(nowActive, Math::max);
            return Mono.delay(Duration.ofMillis(40))
                    .map(ignored -> value)
                    .doOnTerminate(active::decrementAndGet);
        }));
    }
}
