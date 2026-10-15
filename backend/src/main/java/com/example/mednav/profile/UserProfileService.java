package com.example.mednav.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final UserProfileMapper profileMapper;
    private final ObjectMapper objectMapper;

    public UserProfileService(UserProfileMapper profileMapper, ObjectMapper objectMapper) {
        this.profileMapper = profileMapper;
        this.objectMapper = objectMapper;
    }

    public ProfileDocument find(String userId) {
        UserProfileRow row = profileMapper.findByUserId(userId);
        if (row == null) {
            return new ProfileDocument(userId, Map.of(), null);
        }
        return new ProfileDocument(userId, readMap(row.profileJson()), row.updatedAt());
    }

    @Transactional
    public ProfileDocument merge(String userId, Map<String, Object> patch) {
        UserProfileRow existing = profileMapper.findByUserId(userId);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(readMap(existing.profileJson()));
        }
        patch.forEach((key, value) -> {
            if (value != null) {
                merged.put(key, value);
            }
        });
        if (merged.isEmpty()) {
            return new ProfileDocument(userId, Map.of(), existing == null ? null : existing.updatedAt());
        }

        Instant now = Instant.now();
        String profileJson = writeMap(merged);
        if (existing == null) {
            profileMapper.insert(userId, profileJson, now);
        } else {
            profileMapper.updateProfile(userId, profileJson, now);
        }
        return new ProfileDocument(userId, Map.copyOf(merged), now);
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            // H2 PostgreSQL mode exposes a parameter cast to JSONB as a textual JSON node;
            // PostgreSQL returns the object directly. Support both JDBC representations.
            if (node.isTextual()) {
                node = objectMapper.readTree(node.textValue());
            }
            return objectMapper.convertValue(node, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored profile JSON is invalid", exception);
        }
    }

    private String writeMap(Map<String, Object> profile) {
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Profile JSON cannot be serialized", exception);
        }
    }
}
