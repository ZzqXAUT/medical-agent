package com.example.mednav.api;

import java.time.Instant;
import java.util.UUID;

public record RunAcceptedResponse(
        UUID runId, UUID conversationId, UUID sessionId, String status, Instant acceptedAt) {}

