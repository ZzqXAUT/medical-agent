package com.example.mednav.conversation;

import java.time.Instant;

public record ConversationSessionRow(
        String sessionId,
        String conversationId,
        String userId,
        String title,
        String status,
        Instant createdAt,
        Instant lastActivityAt,
        Instant completedAt,
        String completionReason) {}
