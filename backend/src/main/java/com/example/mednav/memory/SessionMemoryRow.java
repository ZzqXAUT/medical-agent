package com.example.mednav.memory;

import java.time.Instant;

public record SessionMemoryRow(
        String sessionId,
        String userId,
        String symptomsJson,
        String preliminaryDiseasesJson,
        String summary,
        Instant createdAt) {}
