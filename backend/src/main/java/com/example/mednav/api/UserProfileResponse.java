package com.example.mednav.api;

import java.time.Instant;
import java.util.Map;

public record UserProfileResponse(
        String userId,
        Map<String, Object> profile,
        Instant updatedAt,
        boolean hasUncommittedSessionUpdates) {}
