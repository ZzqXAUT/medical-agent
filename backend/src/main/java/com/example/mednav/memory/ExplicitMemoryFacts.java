package com.example.mednav.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit user-provided long-term facts. Inferred preferences are intentionally excluded. */
public record ExplicitMemoryFacts(
        List<String> diseaseHistory, Map<String, List<String>> coreConstraints) {

    public ExplicitMemoryFacts {
        diseaseHistory = diseaseHistory == null ? List.of() : List.copyOf(diseaseHistory);
        if (coreConstraints == null) {
            coreConstraints = Map.of();
        } else {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            coreConstraints.forEach((key, values) -> copy.put(
                    key, values == null ? List.of() : List.copyOf(values)));
            coreConstraints = Map.copyOf(copy);
        }
    }

    public static ExplicitMemoryFacts empty() {
        return new ExplicitMemoryFacts(List.of(), Map.of());
    }

    public boolean isEmpty() {
        return diseaseHistory.isEmpty()
                && coreConstraints.values().stream().allMatch(List::isEmpty);
    }
}
