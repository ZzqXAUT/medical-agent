package com.example.mednav.runtime;

import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Serializes Runs within one Session while allowing different Sessions to progress concurrently.
 * A queued item owns the Session until its asynchronous Mono terminates.
 */
@Component
public class SessionRunScheduler {

    private final Map<String, SerialQueue> queues = new ConcurrentHashMap<>();
    private final AtomicInteger threadNumber = new AtomicInteger();
    private final ExecutorService dispatcher = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "mednav-session-run-" + threadNumber.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    public <T> Mono<T> schedule(String sessionId, Supplier<Mono<T>> task) {
        return Mono.create(sink -> {
            queues.compute(sessionId, (ignored, existing) -> {
                SerialQueue queue = existing == null ? new SerialQueue() : existing;
                queue.enqueue(() -> Mono.defer(task)
                        .materialize()
                        .subscribe(
                                signal -> {
                                    if (signal.isOnNext()) {
                                        sink.success(signal.get());
                                    } else if (signal.isOnError()) {
                                        sink.error(signal.getThrowable());
                                    } else {
                                        sink.success();
                                    }
                                },
                                sink::error,
                                () -> complete(sessionId, queue)));
                return queue;
            });
        });
    }

    private void complete(String sessionId, SerialQueue queue) {
        queues.computeIfPresent(sessionId, (ignored, current) -> {
            if (current != queue) {
                return current;
            }
            return queue.completeCurrent() ? null : queue;
        });
    }

    @PreDestroy
    void shutdown() {
        dispatcher.shutdown();
    }

    private final class SerialQueue {
        private final Queue<Runnable> waiting = new ArrayDeque<>();
        private final AtomicInteger state = new AtomicInteger();

        synchronized void enqueue(Runnable task) {
            waiting.add(task);
            if (state.compareAndSet(0, 1)) {
                dispatchNext();
            }
        }

        synchronized boolean completeCurrent() {
            if (waiting.isEmpty()) {
                state.set(0);
                return true;
            }
            dispatchNext();
            return false;
        }

        private void dispatchNext() {
            Runnable next = waiting.poll();
            if (next == null) {
                state.set(0);
                return;
            }
            dispatcher.execute(next);
        }
    }
}
