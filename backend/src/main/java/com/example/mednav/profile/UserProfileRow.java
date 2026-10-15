package com.example.mednav.profile;

import java.time.Instant;

public record UserProfileRow(
        String userId,
        String profileJson,
        String diseaseHistoryJson,
        String coreConstraintsJson,
        Instant updatedAt) {}
