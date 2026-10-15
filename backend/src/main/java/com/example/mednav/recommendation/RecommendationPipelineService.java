package com.example.mednav.recommendation;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class RecommendationPipelineService {

    private final RecommendationGenerationService generationService;
    private final RecommendationVerificationService verificationService;

    public RecommendationPipelineService(
            RecommendationGenerationService generationService,
            RecommendationVerificationService verificationService) {
        this.generationService = generationService;
        this.verificationService = verificationService;
    }

    public Mono<NavigationRecommendationResult> generateAndVerify(
            RecommendationRequest request) {
        return Mono.fromCallable(() -> generationService.generate(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(verificationService::verify);
    }
}
