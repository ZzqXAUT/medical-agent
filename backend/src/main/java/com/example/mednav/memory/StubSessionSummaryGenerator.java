package com.example.mednav.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(
        name = "mednav.agent.mode",
        havingValue = "stub",
        matchIfMissing = true)
public class StubSessionSummaryGenerator implements SessionSummaryGenerator {

    private final SessionSummaryFormatter formatter;

    public StubSessionSummaryGenerator(SessionSummaryFormatter formatter) {
        this.formatter = formatter;
    }

    @Override
    public Mono<String> generate(SessionWorkingMemory memory) {
        return Mono.just(formatter.fallback(memory));
    }
}
