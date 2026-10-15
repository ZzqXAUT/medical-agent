package com.example.mednav.api;

import java.time.Instant;
import java.util.UUID;

public record ConversationCompletionResponse(
        UUID conversationId,
        UUID sessionId,
        String status,
        boolean profilePersisted,
        Instant completedAt) {}
