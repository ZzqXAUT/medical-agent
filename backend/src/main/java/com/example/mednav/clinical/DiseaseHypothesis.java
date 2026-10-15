package com.example.mednav.clinical;

import java.util.List;

public record DiseaseHypothesis(
        String name,
        DiseaseSeverity severity,
        double estimatedProbability,
        List<String> supportingFacts,
        List<String> limitations,
        List<String> targetSpecialties) {

    public DiseaseHypothesis {
        supportingFacts = supportingFacts == null ? List.of() : List.copyOf(supportingFacts);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        targetSpecialties = targetSpecialties == null ? List.of() : List.copyOf(targetSpecialties);
    }
}
