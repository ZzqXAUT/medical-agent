package com.example.mednav.api;

import com.example.mednav.location.LocationStatus;
import java.time.Instant;
import java.util.UUID;

public record LocationTaskAcceptedResponse(
        UUID taskId,
        UUID conversationId,
        long locationRevision,
        LocationStatus status,
        Instant acceptedAt) {}
