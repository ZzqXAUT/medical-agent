package com.example.mednav.recommendation;

import com.example.mednav.clinical.DiseaseSeverity;
import java.util.List;

public record PublicDiseaseHypothesis(
        String name,
        DiseaseSeverity severity,
        double estimatedProbability,
        List<String> supportingFacts,
        List<String> limitations) {

    public PublicDiseaseHypothesis {
        supportingFacts = supportingFacts == null ? List.of() : List.copyOf(supportingFacts);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
