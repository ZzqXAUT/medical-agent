package com.example.mednav.clinical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ClinicalPipelineService {

    private static final Logger log = LoggerFactory.getLogger(ClinicalPipelineService.class);

    private final ClinicalReasoningEngine reasoningEngine;
    private final ClinicalDecisionGate decisionGate;
    private final StubClinicalReasoningEngine deterministicFallback;

    public ClinicalPipelineService(
            ClinicalReasoningEngine reasoningEngine,
            ClinicalDecisionGate decisionGate,
            @Value("${mednav.clinical.max-questions:3}") int maxQuestions,
            @Value("${mednav.clinical.readiness-threshold:0.60}") double limitedThreshold) {
        this.reasoningEngine = reasoningEngine;
        this.decisionGate = decisionGate;
        this.deterministicFallback =
                new StubClinicalReasoningEngine(maxQuestions, limitedThreshold);
    }

    public Mono<ClinicalDecision> assess(ClinicalAssessmentRequest request) {
        return reasoningEngine.assess(request)
                .map(candidate -> decisionGate.validateAndNormalize(candidate, request))
                .onErrorResume(this::canUseDeterministicFallback, error -> {
                    log.warn(
                            "clinical_reasoning_fallback sessionId={} errorType={} rootCauseType={}",
                            request.sessionId(),
                            error.getClass().getSimpleName(),
                            rootCauseType(error));
                    return deterministicFallback
                            .assess(request)
                            .map(candidate -> decisionGate.validateAndNormalize(candidate, request));
                });
    }

    private boolean canUseDeterministicFallback(Throwable error) {
        return error instanceof ClinicalValidationException
                || error instanceof ClinicalModelUnavailableException;
    }

    private String rootCauseType(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }
}
