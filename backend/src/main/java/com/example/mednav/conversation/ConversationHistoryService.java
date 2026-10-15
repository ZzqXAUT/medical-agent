package com.example.mednav.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationHistoryService {

    private static final String DEFAULT_TITLE = "新会话";

    private final ConversationHistoryMapper mapper;
    private final ObjectMapper objectMapper;

    public ConversationHistoryService(
            ConversationHistoryMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void bindSession(
            String userId,
            String sessionId,
            String conversationId,
            Instant createdAt,
            List<ConversationMessageRow> pendingMessages) {
        mapper.insertSession(sessionId, conversationId, userId, DEFAULT_TITLE, createdAt);
        for (ConversationMessageRow message : pendingMessages) {
            mapper.insertMessage(message);
        }
    }

    @Transactional
    public void append(ConversationMessageRow message) {
        mapper.insertMessage(message);
        mapper.touch(message.sessionId(), message.createdAt());
    }

    public void touch(String sessionId, Instant at) {
        mapper.touch(sessionId, at);
    }

    public void setInitialTitle(String sessionId, String source) {
        String normalized = source == null ? "" : source.strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return;
        }
        String title = normalized.length() <= 36
                ? normalized
                : normalized.substring(0, 36) + "…";
        mapper.setInitialTitle(sessionId, title);
    }

    public List<ConversationSessionRow> sessions(String userId) {
        return mapper.findSessionsByUserId(userId);
    }

    public ConversationSessionRow ownedSession(String userId, String sessionId) {
        return mapper.findOwnedSession(sessionId, userId);
    }

    public List<ConversationMessageRow> messages(String sessionId) {
        return mapper.findMessages(sessionId);
    }

    public void complete(String sessionId, Instant completedAt, boolean idle) {
        mapper.complete(sessionId, completedAt, idle ? "IDLE_TIMEOUT" : "EXPLICIT");
    }

    public String serializePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Conversation payload cannot be serialized", exception);
        }
    }

    public JsonNode parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored conversation payload is invalid", exception);
        }
    }
}
