package com.example.mednav.clinical;

import java.util.List;

public record SymptomCase(
        String narrative,
        String chiefComplaint,
        String duration,
        Integer severityScore,
        List<String> associatedSymptoms,
        List<String> redFlags,
        List<String> knownFacts) {

    public SymptomCase {
        associatedSymptoms = associatedSymptoms == null ? List.of() : List.copyOf(associatedSymptoms);
        redFlags = redFlags == null ? List.of() : List.copyOf(redFlags);
        knownFacts = knownFacts == null ? List.of() : List.copyOf(knownFacts);
    }
}
