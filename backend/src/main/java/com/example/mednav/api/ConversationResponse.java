package com.example.mednav.api;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(UUID conversationId, UUID sessionId, Instant createdAt) {}

