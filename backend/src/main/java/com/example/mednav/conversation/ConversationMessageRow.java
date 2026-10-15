package com.example.mednav.conversation;

import java.time.Instant;

public record ConversationMessageRow(
        String id,
        String sessionId,
        String runId,
        long sequenceNo,
        String role,
        String content,
        String messageType,
        String structuredPayload,
        Instant createdAt) {}
