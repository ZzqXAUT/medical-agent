package com.example.mednav.recommendation;

import java.util.List;

public record DiseaseRecommendation(
        PublicDiseaseHypothesis hypothesis,
        int diseaseRank,
        String recommendationReason,
        ConfidenceResult diseaseConfidence,
        List<HospitalRecommendation> hospitals,
        List<String> evidenceIds,
        List<String> limitations) {

    public DiseaseRecommendation {
        hospitals = hospitals == null ? List.of() : List.copyOf(hospitals);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public DiseaseRecommendation withRankAndHospitals(
            int rank, List<HospitalRecommendation> replacements) {
        return new DiseaseRecommendation(
                hypothesis,
                rank,
                recommendationReason,
                diseaseConfidence,
                replacements,
                evidenceIds,
                limitations);
    }
}
