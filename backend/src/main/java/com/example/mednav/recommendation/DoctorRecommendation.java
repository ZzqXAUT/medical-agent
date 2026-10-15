package com.example.mednav.recommendation;

import java.util.List;

public record DoctorRecommendation(
        String npi,
        String name,
        String credential,
        String primarySpecialty,
        List<String> secondarySpecialties,
        String medicalSchool,
        Integer graduationYear,
        String affiliationFacilityId,
        String recommendationReason,
        List<String> recommendationReasons,
        List<String> evidenceIds,
        ConfidenceResult doctorConfidence) {

    public DoctorRecommendation {
        secondarySpecialties = secondarySpecialties == null
                ? List.of()
                : List.copyOf(secondarySpecialties);
        recommendationReasons = recommendationReasons == null
                ? List.of()
                : List.copyOf(recommendationReasons);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
