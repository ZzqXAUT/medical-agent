package com.example.mednav.memory;

import java.util.List;
import java.util.Map;

/** Redis representation of database-backed long-term user memory. */
public record UserMemorySnapshot(
        String userId,
        boolean hasLongTermProfile,
        Map<String, Object> profile,
        List<String> diseaseHistory,
        Map<String, Object> coreConstraints,
        List<SessionMemoryRecord> history) {

    public UserMemorySnapshot {
        profile = profile == null ? Map.of() : Map.copyOf(profile);
        diseaseHistory = diseaseHistory == null ? List.of() : List.copyOf(diseaseHistory);
        coreConstraints = coreConstraints == null ? Map.of() : Map.copyOf(coreConstraints);
        history = history == null ? List.of() : List.copyOf(history);
    }
}
