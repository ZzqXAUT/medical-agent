package com.example.mednav.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record ConversationMessageResponse(
        UUID id,
        String role,
        String content,
        String messageType,
        JsonNode structuredPayload,
        Instant createdAt) {}
