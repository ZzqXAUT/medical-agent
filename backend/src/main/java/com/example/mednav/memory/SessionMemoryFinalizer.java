package com.example.mednav.memory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class SessionMemoryFinalizer {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryFinalizer.class);

    private final MemoryService memoryService;
    private final MemoryPersistenceService persistence;
    private final SessionSummaryGenerator summaryGenerator;
    private final SessionSummaryFormatter fallbackFormatter;
    private final Counter completedCounter;
    private final Counter failedCounter;
    private final Counter degradedSummaryCounter;
    private final Timer finalizationTimer;
    private final MeterRegistry meterRegistry;
    private final Map<String, Mono<SessionFinalizationResult>> inFlight = new ConcurrentHashMap<>();

    public SessionMemoryFinalizer(
            MemoryService memoryService,
            MemoryPersistenceService persistence,
            SessionSummaryGenerator summaryGenerator,
            SessionSummaryFormatter fallbackFormatter,
            MeterRegistry meterRegistry) {
        this.memoryService = memoryService;
        this.persistence = persistence;
        this.summaryGenerator = summaryGenerator;
        this.fallbackFormatter = fallbackFormatter;
        this.completedCounter = meterRegistry.counter("mednav.memory.finalization.completed");
        this.failedCounter = meterRegistry.counter("mednav.memory.finalization.failed");
        this.degradedSummaryCounter =
                meterRegistry.counter("mednav.memory.summary.degraded");
        this.finalizationTimer = meterRegistry.timer("mednav.memory.finalization.duration");
        this.meterRegistry = meterRegistry;
    }

    public Mono<SessionFinalizationResult> finalizeSession(
            String userId, String sessionId, Instant createdAt) {
        return inFlight.computeIfAbsent(sessionId, ignored -> Mono.defer(() -> finalizeOnce(
                        userId, sessionId, createdAt))
                .cache()
                .doFinally(signal -> inFlight.remove(sessionId)));
    }

    private Mono<SessionFinalizationResult> finalizeOnce(
            String userId, String sessionId, Instant createdAt) {
        Timer.Sample timerSample = Timer.start(meterRegistry);
        final SessionWorkingMemory working;
        try {
            working = memoryService.working(userId, sessionId);
        } catch (IllegalStateException missing) {
            if (memoryService.isFinalized(userId, sessionId)) {
                return Mono.just(new SessionFinalizationResult(false, true, true, true, false));
            }
            return Mono.error(missing);
        }

        boolean[] degraded = {false};
        return summaryGenerator.generate(working)
                .onErrorResume(error -> {
                    degraded[0] = true;
                    degradedSummaryCounter.increment();
                    log.warn(
                            "session_summary_degraded sessionId={} errorType={}",
                            sessionId,
                            error.getClass().getSimpleName());
                    return Mono.just(fallbackFormatter.fallback(working));
                })
                .map(working::withSummary)
                .flatMap(finalMemory -> Mono.fromCallable(() -> {
                            memoryService.updateFinalSummary(finalMemory);
                            MemoryCommitResult committed = persistence.commit(finalMemory, createdAt);
                            memoryService.refreshUser(userId);
                            memoryService.deleteWorking(sessionId);
                            log.info(
                                    "session_memory_finalized sessionId={} profilePersisted={} summaryDegraded={}",
                                    sessionId,
                                    committed.profilePersisted(),
                                    degraded[0]);
                            return new SessionFinalizationResult(
                                    committed.profilePersisted(),
                                    committed.clinicalPersisted(),
                                    committed.summaryPersisted(),
                                    false,
                                    degraded[0]);
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnSuccess(ignored -> completedCounter.increment())
                .doOnError(ignored -> failedCounter.increment())
                .doFinally(ignored -> timerSample.stop(finalizationTimer));
    }
}
