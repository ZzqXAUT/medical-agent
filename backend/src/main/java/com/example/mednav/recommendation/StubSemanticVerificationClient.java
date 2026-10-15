package com.example.mednav.recommendation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "mednav.agent.mode", havingValue = "stub", matchIfMissing = true)
public class StubSemanticVerificationClient implements SemanticVerificationClient {
    @Override
    public Mono<SemanticVerificationReport> verify(NavigationRecommendationResult result) {
        return Mono.just(new SemanticVerificationReport(java.util.List.of()));
    }
}
