package com.example.mednav.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Parses AgentScope structured data and safely falls back to a JSON text response. */
@Component
public class AgentScopeStructuredResponseParser {

    private final ObjectMapper objectMapper;

    public AgentScopeStructuredResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T parse(Msg message, Class<T> responseType) {
        if (message.hasStructuredData()) {
            T structured = message.getStructuredData(responseType);
            if (structured != null) {
                return structured;
            }
        }

        String json = extractJsonObject(message.getTextContent());
        try {
            return objectMapper.readValue(json, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "AgentScope response could not be parsed as " + responseType.getSimpleName(),
                    exception);
        }
    }

    private String extractJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("AgentScope response did not contain structured data or text");
        }
        String candidate = text.strip();
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("AgentScope text response did not contain a JSON object");
        }
        return candidate.substring(start, end + 1);
    }
}
