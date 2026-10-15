package com.example.mednav.api;

import java.time.Instant;
import java.util.UUID;

public record UserSessionResponse(
        UUID sessionId,
        UUID conversationId,
        String title,
        String status,
        boolean resumable,
        Instant createdAt,
        Instant lastActivityAt,
        Instant completedAt) {}
