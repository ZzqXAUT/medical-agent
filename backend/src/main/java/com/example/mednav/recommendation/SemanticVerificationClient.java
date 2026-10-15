package com.example.mednav.recommendation;

import reactor.core.publisher.Mono;

public interface SemanticVerificationClient {
    Mono<SemanticVerificationReport> verify(NavigationRecommendationResult result);
}
