package com.example.mednav.agent;

import reactor.core.publisher.Mono;

public interface MedicalAgentRuntime {

    Mono<AgentRunResult> execute(AgentRunCommand command);
}

