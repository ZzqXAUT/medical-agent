package com.example.mednav.recommendation;

import com.example.mednav.route.RouteMeasure;
import java.util.List;

public record HospitalRecommendation(
        String hospitalId,
        String name,
        int hospitalRankWithinDisease,
        Integer cmsRating,
        String address,
        String recommendationReason,
        List<String> diseaseHospitalMatchReasons,
        RouteMeasure drive,
        RouteMeasure transit,
        RouteMeasure walk,
        List<DoctorRecommendation> doctors,
        ConfidenceResult hospitalConfidence,
        ConfidenceResult explanationConfidence,
        List<String> evidenceIds,
        List<String> limitations) {

    public HospitalRecommendation {
        diseaseHospitalMatchReasons = diseaseHospitalMatchReasons == null
                ? List.of()
                : List.copyOf(diseaseHospitalMatchReasons);
        doctors = doctors == null ? List.of() : List.copyOf(doctors);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public HospitalRecommendation withRank(int rank) {
        return new HospitalRecommendation(
                hospitalId,
                name,
                rank,
                cmsRating,
                address,
                recommendationReason,
                diseaseHospitalMatchReasons,
                drive,
                transit,
                walk,
                doctors,
                hospitalConfidence,
                explanationConfidence,
                evidenceIds,
                limitations);
    }
}
