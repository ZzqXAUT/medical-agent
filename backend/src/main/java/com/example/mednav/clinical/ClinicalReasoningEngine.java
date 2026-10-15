package com.example.mednav.clinical;

import reactor.core.publisher.Mono;

public interface ClinicalReasoningEngine {

    Mono<ClinicalDecision> assess(ClinicalAssessmentRequest request);
}
