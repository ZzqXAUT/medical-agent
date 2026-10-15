package com.example.mednav.agui;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentEvent(
        UUID eventId,
        String type,
        UUID conversationId,
        UUID sessionId,
        UUID runId,
        String messageId,
        long sequence,
        Instant timestamp,
        String delta,
        String content,
        String errorCode,
        Map<String, Object> data) {

    public AgentEvent(
            UUID eventId,
            String type,
            UUID conversationId,
            UUID sessionId,
            UUID runId,
            String messageId,
            long sequence,
            Instant timestamp,
            String delta,
            String content,
            String errorCode) {
        this(
                eventId,
                type,
                conversationId,
                sessionId,
                runId,
                messageId,
                sequence,
                timestamp,
                delta,
                content,
                errorCode,
                null);
    }
}
