package com.example.mednav.recommendation;

import com.example.mednav.clinical.DiseaseHypothesis;
import com.example.mednav.clinical.SymptomCase;
import com.example.mednav.cms.CmsDoctorCandidate;
import com.example.mednav.cms.CmsHospital;
import com.example.mednav.route.HospitalRoute;
import com.example.mednav.route.RouteElementStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ConfidenceService {

    public static final String POLICY_VERSION = "confidence-v1";

    public ConfidenceResult disease(DiseaseHypothesis hypothesis, SymptomCase symptoms) {
        Map<String, Integer> components = new LinkedHashMap<>();
        components.put("userFactCoverage", hypothesis.supportingFacts().isEmpty() ? 0 : 100);
        components.put(
                "symptomConsistency",
                symptoms != null && symptoms.chiefComplaint() != null ? 100 : 40);
        components.put("schemaValidity", validDisease(hypothesis) ? 100 : 0);
        components.put("inferenceConsistency", hypothesis.targetSpecialties().isEmpty() ? 40 : 100);
        return weighted(components, Map.of(
                "userFactCoverage", 35,
                "symptomConsistency", 25,
                "schemaValidity", 20,
                "inferenceConsistency", 20));
    }

    public ConfidenceResult hospital(
            CmsHospital hospital, HospitalRoute route, boolean hasSpecialtyDoctor) {
        Map<String, Integer> components = new LinkedHashMap<>();
        components.put("ratingValidity", hospital.overallRating() == null ? 60 : 100);
        components.put("specialtyCoverage", hasSpecialtyDoctor ? 100 : 60);
        components.put("locationValidity", 100);
        components.put(
                "routeValidity",
                route != null
                                && route.status() == RouteElementStatus.SUCCESS
                                && route.drive() != null
                        ? 100
                        : 40);
        components.put("dataFreshness", 100);
        return weighted(components, Map.of(
                "ratingValidity", 15,
                "specialtyCoverage", 30,
                "locationValidity", 20,
                "routeValidity", 20,
                "dataFreshness", 15));
    }

    public ConfidenceResult doctor(CmsDoctorCandidate doctor) {
        Map<String, Integer> components = new LinkedHashMap<>();
        components.put("entityValidity", validNpi(doctor.npi()) && hasName(doctor) ? 100 : 0);
        components.put("specialtyMatch", doctor.matchedSpecialties().isEmpty() ? 0 : 100);
        components.put("affiliationEvidence", doctor.facilityId() == null ? 0 : 100);
        components.put("dataFreshness", doctor.snapshotId() == null ? 0 : 100);
        return weighted(components, Map.of(
                "entityValidity", 25,
                "specialtyMatch", 30,
                "affiliationEvidence", 30,
                "dataFreshness", 15));
    }

    public ConfidenceResult explanation(List<String> reasons, List<String> evidenceIds) {
        Map<String, Integer> components = new LinkedHashMap<>();
        components.put("claimEvidenceCoverage", evidenceIds.isEmpty() ? 0 : 100);
        components.put("consistency", reasons.isEmpty() ? 0 : 100);
        components.put("completeness", reasons.size() >= 2 ? 100 : 70);
        return weighted(components, Map.of(
                "claimEvidenceCoverage", 45,
                "consistency", 35,
                "completeness", 20));
    }

    public ConfidenceResult overall(List<DiseaseRecommendation> diseases) {
        if (diseases.isEmpty()) {
            return result(0, Map.of(
                    "verifiedNodeAverage", 0,
                    "hospitalBranchCoverage", 0,
                    "doctorCoverage", 0));
        }
        int sum = 0;
        int count = 0;
        int diseasesWithHospitals = 0;
        int hospitalCount = 0;
        int hospitalsWithDoctors = 0;
        for (DiseaseRecommendation disease : diseases) {
            sum += disease.diseaseConfidence().score();
            count++;
            if (!disease.hospitals().isEmpty()) diseasesWithHospitals++;
            for (HospitalRecommendation hospital : disease.hospitals()) {
                hospitalCount++;
                if (!hospital.doctors().isEmpty()) hospitalsWithDoctors++;
                sum += hospital.hospitalConfidence().score();
                sum += hospital.explanationConfidence().score();
                count += 2;
                for (DoctorRecommendation doctor : hospital.doctors()) {
                    sum += doctor.doctorConfidence().score();
                    count++;
                }
            }
        }
        int nodeAverage = Math.round((float) sum / count);
        int hospitalCoverage = Math.round(100.0f * diseasesWithHospitals / diseases.size());
        int doctorCoverage = hospitalCount == 0
                ? 0
                : Math.round(100.0f * hospitalsWithDoctors / hospitalCount);
        int score = Math.round(
                nodeAverage * 0.60f + hospitalCoverage * 0.25f + doctorCoverage * 0.15f);
        Map<String, Integer> components = new LinkedHashMap<>();
        components.put("verifiedNodeAverage", nodeAverage);
        components.put("hospitalBranchCoverage", hospitalCoverage);
        components.put("doctorCoverage", doctorCoverage);
        return result(score, components);
    }

    private ConfidenceResult weighted(
            Map<String, Integer> components, Map<String, Integer> weights) {
        int score = 0;
        for (Map.Entry<String, Integer> entry : components.entrySet()) {
            score += entry.getValue() * weights.get(entry.getKey());
        }
        return result(Math.round(score / 100.0f), components);
    }

    private ConfidenceResult result(int score, Map<String, Integer> components) {
        ConfidenceBand band = score >= 85
                ? ConfidenceBand.HIGH
                : score >= 65 ? ConfidenceBand.MEDIUM : ConfidenceBand.LOW;
        return new ConfidenceResult(score, band, POLICY_VERSION, components);
    }

    private boolean validDisease(DiseaseHypothesis hypothesis) {
        return hypothesis.name() != null
                && !hypothesis.name().isBlank()
                && hypothesis.severity() != null
                && hypothesis.estimatedProbability() >= 0
                && hypothesis.estimatedProbability() <= 1;
    }

    private boolean validNpi(String npi) {
        return npi != null && npi.matches("\\d{10}");
    }

    private boolean hasName(CmsDoctorCandidate doctor) {
        return doctor.firstName() != null
                && !doctor.firstName().isBlank()
                && doctor.lastName() != null
                && !doctor.lastName().isBlank();
    }
}
