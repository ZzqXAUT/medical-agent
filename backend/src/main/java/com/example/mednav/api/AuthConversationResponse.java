package com.example.mednav.api;

import java.util.UUID;

public record AuthConversationResponse(
        UUID runId,
        UUID conversationId,
        String userId,
        String username,
        boolean newUser,
        String status) {}
