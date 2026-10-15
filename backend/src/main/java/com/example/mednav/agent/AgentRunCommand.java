package com.example.mednav.agent;

import java.util.UUID;
import java.util.function.Consumer;
import com.example.mednav.agui.AgentEvent;

public record AgentRunCommand(
        UUID conversationId,
        UUID sessionId,
        UUID runId,
        String userMessage,
        Consumer<AgentEvent> eventPublisher) {}

