package com.example.mednav.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MemoryJsonCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public MemoryJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> readMap(String json) {
        JsonNode node = readNode(json);
        return node == null || node.isNull() ? Map.of() : objectMapper.convertValue(node, MAP_TYPE);
    }

    public List<String> readStringList(String json) {
        JsonNode node = readNode(json);
        return node == null || node.isNull()
                ? List.of()
                : List.copyOf(objectMapper.convertValue(node, STRING_LIST_TYPE));
    }

    public <T> T read(String json, Class<T> type) {
        JsonNode node = readNode(json);
        return node == null || node.isNull() || node.isArray() && node.isEmpty()
                ? null
                : objectMapper.convertValue(node, type);
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Memory JSON cannot be serialized", exception);
        }
    }

    private JsonNode readNode(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.textValue());
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored memory JSON is invalid", exception);
        }
    }
}
