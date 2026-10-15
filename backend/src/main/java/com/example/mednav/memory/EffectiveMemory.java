package com.example.mednav.memory;

import java.util.List;
import java.util.Map;

/** Runtime-only merge; it is never written back as an independent fact source. */
public record EffectiveMemory(
        Map<String, Object> profile,
        List<String> diseaseHistory,
        Map<String, Object> coreConstraints,
        SessionWorkingMemory currentSession,
        List<SessionMemoryRecord> history) {}
