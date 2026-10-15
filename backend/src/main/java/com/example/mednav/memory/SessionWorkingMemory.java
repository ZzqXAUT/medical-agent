package com.example.mednav.memory;

import com.example.mednav.clinical.SymptomCase;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Redis working copy containing only updates produced by the current Session. */
public record SessionWorkingMemory(
        String sessionId,
        String userId,
        Map<String, Object> profile,
        List<String> diseaseHistory,
        Map<String, Object> coreConstraints,
        SymptomCase symptoms,
        List<String> preliminaryDiseases,
        String summary,
        Instant updatedAt) {

    public SessionWorkingMemory {
        profile = profile == null ? Map.of() : Map.copyOf(profile);
        diseaseHistory = diseaseHistory == null ? List.of() : List.copyOf(diseaseHistory);
        coreConstraints = coreConstraints == null ? Map.of() : Map.copyOf(coreConstraints);
        preliminaryDiseases = preliminaryDiseases == null
                ? List.of()
                : List.copyOf(preliminaryDiseases).stream().limit(3).toList();
        summary = summary == null ? "" : summary;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public static SessionWorkingMemory empty(String sessionId, String userId) {
        return new SessionWorkingMemory(
                sessionId,
                userId,
                Map.of(),
                List.of(),
                Map.of(),
                null,
                List.of(),
                "",
                Instant.now());
    }

    public SessionWorkingMemory withProfilePatch(Map<String, Object> patch) {
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(profile);
        patch.forEach((key, value) -> {
            if (value != null) {
                merged.put(key, value);
            }
        });
        return new SessionWorkingMemory(
                sessionId,
                userId,
                merged,
                diseaseHistory,
                coreConstraints,
                symptoms,
                preliminaryDiseases,
                summary,
                Instant.now());
    }

    public SessionWorkingMemory withClinical(
            SymptomCase replacementSymptoms, List<String> replacementDiseases) {
        return new SessionWorkingMemory(
                sessionId,
                userId,
                profile,
                diseaseHistory,
                coreConstraints,
                replacementSymptoms,
                replacementDiseases,
                summary,
                Instant.now());
    }

    public SessionWorkingMemory withExplicitFacts(ExplicitMemoryFacts facts) {
        java.util.LinkedHashSet<String> mergedDiseases =
                new java.util.LinkedHashSet<>(diseaseHistory);
        mergedDiseases.addAll(facts.diseaseHistory());

        java.util.LinkedHashMap<String, Object> mergedConstraints =
                new java.util.LinkedHashMap<>(coreConstraints);
        facts.coreConstraints().forEach((key, additions) -> {
            java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
            Object existing = mergedConstraints.get(key);
            if (existing instanceof java.util.Collection<?> collection) {
                collection.stream().map(String::valueOf).forEach(values::add);
            }
            values.addAll(additions);
            mergedConstraints.put(key, List.copyOf(values));
        });
        return new SessionWorkingMemory(
                sessionId,
                userId,
                profile,
                List.copyOf(mergedDiseases),
                Map.copyOf(mergedConstraints),
                symptoms,
                preliminaryDiseases,
                summary,
                Instant.now());
    }

    public SessionWorkingMemory withSummary(String replacementSummary) {
        return new SessionWorkingMemory(
                sessionId,
                userId,
                profile,
                diseaseHistory,
                coreConstraints,
                symptoms,
                preliminaryDiseases,
                replacementSummary,
                Instant.now());
    }
}
