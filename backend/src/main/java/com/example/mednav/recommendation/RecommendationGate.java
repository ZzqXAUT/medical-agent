package com.example.mednav.recommendation;

import com.example.mednav.clinical.DiseaseSeverity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RecommendationGate {

    public List<VerificationFinding> validate(NavigationRecommendationResult result) {
        List<VerificationFinding> findings = new ArrayList<>();
        Set<String> evidence = new HashSet<>();
        result.evidence().forEach(item -> evidence.add(item.evidenceId()));
        if (result.diseases().size() > 3) {
            findings.add(error("$.diseases", "DISEASE_LIMIT", "Disease limit exceeded", "TRUNCATE"));
        }
        double previousProbability = Double.POSITIVE_INFINITY;
        Set<String> diseaseNames = new HashSet<>();
        for (int diseaseIndex = 0; diseaseIndex < result.diseases().size(); diseaseIndex++) {
            DiseaseRecommendation disease = result.diseases().get(diseaseIndex);
            String path = "$.diseases[" + diseaseIndex + "]";
            if (disease.diseaseRank() != diseaseIndex + 1
                    || disease.hypothesis().estimatedProbability() > previousProbability) {
                findings.add(error(path, "DISEASE_ORDER", "Disease order or rank is invalid", "REORDER"));
            }
            previousProbability = disease.hypothesis().estimatedProbability();
            if (!diseaseNames.add(disease.hypothesis().name())) {
                findings.add(error(path, "DUPLICATE_DISEASE", "Duplicate disease branch", "REMOVE_DISEASE"));
            }
            if (disease.hypothesis().supportingFacts().isEmpty()
                    || disease.recommendationReason() == null
                    || disease.recommendationReason().isBlank()
                    || !allEvidenceExists(disease.evidenceIds(), evidence)) {
                findings.add(error(path, "DISEASE_EVIDENCE", "Disease evidence is missing", "REMOVE_DISEASE"));
            }
            validateHospitals(result, disease, diseaseIndex, evidence, findings);
        }
        return List.copyOf(findings);
    }

    private void validateHospitals(
            NavigationRecommendationResult result,
            DiseaseRecommendation disease,
            int diseaseIndex,
            Set<String> evidence,
            List<VerificationFinding> findings) {
        if (disease.hospitals().size() > 2) {
            findings.add(error(
                    "$.diseases[" + diseaseIndex + "].hospitals",
                    "HOSPITAL_LIMIT",
                    "Hospital limit exceeded",
                    "TRUNCATE"));
        }
        Set<String> ids = new HashSet<>();
        HospitalRecommendation previous = null;
        for (int hospitalIndex = 0; hospitalIndex < disease.hospitals().size(); hospitalIndex++) {
            HospitalRecommendation hospital = disease.hospitals().get(hospitalIndex);
            String path = "$.diseases[" + diseaseIndex + "].hospitals[" + hospitalIndex + "]";
            if (hospital.hospitalRankWithinDisease() != hospitalIndex + 1
                    || previous != null && compareHospitals(previous, hospital) > 0) {
                findings.add(error(path, "HOSPITAL_ORDER", "Hospital order or rank is invalid", "REORDER"));
            }
            previous = hospital;
            if (!ids.add(hospital.hospitalId())) {
                findings.add(error(path, "DUPLICATE_HOSPITAL", "Duplicate hospital in disease branch", "REMOVE_HOSPITAL"));
            }
            if (!withinRange(disease.hypothesis().severity(), hospital)
                    || hospital.recommendationReason() == null
                    || hospital.recommendationReason().isBlank()
                    || !allEvidenceExists(hospital.evidenceIds(), evidence)) {
                findings.add(error(path, "HOSPITAL_HARD_GATE", "Hospital range or evidence gate failed", "REMOVE_HOSPITAL"));
            }
            validateDoctors(result, hospital, path, evidence, findings);
        }
    }

    private void validateDoctors(
            NavigationRecommendationResult result,
            HospitalRecommendation hospital,
            String hospitalPath,
            Set<String> evidence,
            List<VerificationFinding> findings) {
        if (hospital.doctors().size() > 2) {
            findings.add(error(hospitalPath + ".doctors", "DOCTOR_LIMIT", "Doctor limit exceeded", "TRUNCATE"));
        }
        Set<String> npis = new HashSet<>();
        String previousNpi = null;
        for (int doctorIndex = 0; doctorIndex < hospital.doctors().size(); doctorIndex++) {
            DoctorRecommendation doctor = hospital.doctors().get(doctorIndex);
            String path = hospitalPath + ".doctors[" + doctorIndex + "]";
            boolean invalid = doctor.npi() == null
                    || !doctor.npi().matches("\\d{10}")
                    || !hospital.hospitalId().equals(doctor.affiliationFacilityId())
                    || doctor.recommendationReason() == null
                    || doctor.recommendationReason().isBlank()
                    || doctor.recommendationReasons().isEmpty()
                    || !allEvidenceExists(doctor.evidenceIds(), evidence);
            if (invalid) {
                findings.add(error(path, "DOCTOR_HARD_GATE", "Doctor entity, affiliation, or evidence gate failed", "REMOVE_DOCTOR"));
            }
            if (!npis.add(doctor.npi())) {
                findings.add(error(path, "DUPLICATE_DOCTOR", "Duplicate doctor in hospital branch", "REMOVE_DOCTOR"));
            }
            if (previousNpi != null
                    && doctor.npi() != null
                    && previousNpi.compareTo(doctor.npi()) > 0) {
                findings.add(error(path, "DOCTOR_STABLE_ORDER", "Doctor NPI order is not stable", "REORDER"));
            }
            if (doctor.npi() != null) {
                previousNpi = doctor.npi();
            }
            if (!result.cmsSnapshotId().equals(extractSnapshot(doctor.evidenceIds()))) {
                findings.add(error(path, "SNAPSHOT_MISMATCH", "Doctor evidence crosses CMS snapshots", "REMOVE_DOCTOR"));
            }
        }
    }

    private boolean withinRange(DiseaseSeverity severity, HospitalRecommendation hospital) {
        if (severity == DiseaseSeverity.SEVERE) return true;
        if (hospital.drive() == null) return false;
        BigDecimal limit = severity == DiseaseSeverity.MODERATE
                ? new BigDecimal("5.0")
                : new BigDecimal("10.0");
        return hospital.drive().distanceMiles().compareTo(limit) <= 0;
    }

    private int compareHospitals(HospitalRecommendation left, HospitalRecommendation right) {
        Comparator<Integer> ratings = Comparator.nullsLast(Comparator.reverseOrder());
        int rating = ratings.compare(left.cmsRating(), right.cmsRating());
        if (rating != 0) return rating;
        Comparator<BigDecimal> distances = Comparator.nullsLast(Comparator.naturalOrder());
        int distance = distances.compare(
                left.drive() == null ? null : left.drive().distanceMiles(),
                right.drive() == null ? null : right.drive().distanceMiles());
        return distance != 0 ? distance : left.hospitalId().compareTo(right.hospitalId());
    }

    private boolean allEvidenceExists(List<String> ids, Set<String> evidence) {
        return !ids.isEmpty() && evidence.containsAll(ids);
    }

    private String extractSnapshot(List<String> ids) {
        return ids.stream()
                .filter(id -> id.startsWith("cms:"))
                .map(id -> id.split(":", 4))
                .filter(parts -> parts.length >= 3)
                .map(parts -> parts[1])
                .findFirst()
                .orElse("");
    }

    private VerificationFinding error(
            String path, String code, String message, String repairAction) {
        return new VerificationFinding(
                path, code, VerificationSeverity.ERROR, message, repairAction, false);
    }
}
