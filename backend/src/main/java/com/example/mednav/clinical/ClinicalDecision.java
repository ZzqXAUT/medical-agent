package com.example.mednav.clinical;

import java.util.List;

public record ClinicalDecision(
        ClinicalReadiness decision,
        List<MissingInformation> missingInformation,
        String proposedQuestion,
        double readinessConfidence,
        SymptomCase symptomCase,
        List<DiseaseHypothesis> hypotheses) {

    public ClinicalDecision {
        missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
    }

    public ClinicalDecision withDecision(
            ClinicalReadiness replacement, String question, List<DiseaseHypothesis> diseases) {
        return new ClinicalDecision(
                replacement,
                missingInformation,
                question,
                readinessConfidence,
                symptomCase,
                diseases);
    }
}
