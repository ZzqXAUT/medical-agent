package com.example.mednav.memory;

import reactor.core.publisher.Mono;

public interface SessionSummaryGenerator {

    Mono<String> generate(SessionWorkingMemory memory);
}
