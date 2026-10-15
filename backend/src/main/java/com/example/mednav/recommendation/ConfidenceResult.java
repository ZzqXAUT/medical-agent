package com.example.mednav.recommendation;

import java.util.Map;

public record ConfidenceResult(
        int score,
        ConfidenceBand band,
        String policyVersion,
        Map<String, Integer> components) {

    public ConfidenceResult {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Confidence score must be between 0 and 100");
        }
        components = components == null ? Map.of() : Map.copyOf(components);
    }
}
