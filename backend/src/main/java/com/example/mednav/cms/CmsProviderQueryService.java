package com.example.mednav.cms;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CmsProviderQueryService {

    private final CmsProviderQueryMapper mapper;

    public CmsProviderQueryService(CmsProviderQueryMapper mapper) {
        this.mapper = mapper;
    }

    public List<CmsHospital> hospitalsByCity(String state, String city) {
        return mapper.findActiveHospitalsByCity(state.strip(), city.strip());
    }

    public CmsHospitalSearchResult hospitalSearchByCity(String state, String city) {
        String snapshotId = mapper.findActiveSnapshotId();
        if (snapshotId == null || snapshotId.isBlank()) {
            return new CmsHospitalSearchResult(null, List.of());
        }
        return new CmsHospitalSearchResult(
                snapshotId,
                mapper.findHospitalsBySnapshotAndCity(
                        snapshotId, state.strip(), city.strip()));
    }

    public List<CmsDoctorCandidate> doctorsAtHospital(
            String facilityId, List<String> targetSpecialties) {
        List<String> normalized = normalize(targetSpecialties);
        return normalized.isEmpty()
                ? List.of()
                : aggregateCandidates(mapper.findActiveDoctorMatches(facilityId, normalized))
                        .toList();
    }

    public List<CmsHospital> hospitalsBySnapshotAndIds(
            String snapshotId, List<String> facilityIds) {
        if (snapshotId == null || snapshotId.isBlank() || facilityIds == null) {
            return List.of();
        }
        List<String> normalizedIds = facilityIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        return normalizedIds.isEmpty()
                ? List.of()
                : mapper.findHospitalsBySnapshotAndIds(snapshotId, normalizedIds);
    }

    public List<CmsDoctorCandidate> doctorsAtHospital(
            String snapshotId,
            String facilityId,
            List<String> targetSpecialties,
            int limit) {
        if (snapshotId == null || snapshotId.isBlank() || limit <= 0) {
            return List.of();
        }
        List<String> normalized = normalize(targetSpecialties);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return aggregateCandidates(mapper.findDoctorMatches(snapshotId, facilityId, normalized))
                .limit(limit)
                .toList();
    }

    private List<String> normalize(List<String> targetSpecialties) {
        if (targetSpecialties == null || targetSpecialties.isEmpty()) {
            return List.of();
        }
        return targetSpecialties.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private java.util.stream.Stream<CmsDoctorCandidate> aggregateCandidates(
            List<CmsDoctorMatchRow> rows) {
        Map<String, CandidateBuilder> candidates = new LinkedHashMap<>();
        for (CmsDoctorMatchRow row : rows) {
            candidates.computeIfAbsent(row.npi(), ignored -> new CandidateBuilder(row))
                    .addSpecialty(row);
        }
        return candidates.values().stream().map(CandidateBuilder::build);
    }

    public List<String> activeSpecialtyVocabulary() {
        return mapper.findActiveSpecialtyVocabulary();
    }

    private static final class CandidateBuilder {
        private final CmsDoctorMatchRow first;
        private final Map<String, CmsSpecialtyFact> specialties = new LinkedHashMap<>();

        private CandidateBuilder(CmsDoctorMatchRow first) {
            this.first = first;
        }

        private CandidateBuilder addSpecialty(CmsDoctorMatchRow row) {
            specialties.putIfAbsent(
                    row.specialtyType() + "|" + row.normalizedSpecialty(),
                    new CmsSpecialtyFact(
                            row.specialtyType(), row.specialtyName(), row.normalizedSpecialty()));
            return this;
        }

        private CmsDoctorCandidate build() {
            return new CmsDoctorCandidate(
                    first.snapshotId(),
                    first.facilityId(),
                    first.npi(),
                    first.firstName(),
                    first.middleName(),
                    first.lastName(),
                    first.suffix(),
                    first.credential(),
                    first.medicalSchool(),
                    first.graduationYear(),
                    first.facilityType(),
                    List.copyOf(specialties.values()));
        }
    }
}
