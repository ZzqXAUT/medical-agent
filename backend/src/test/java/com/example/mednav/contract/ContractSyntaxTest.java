package com.example.mednav.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ContractSyntaxTest {

    @Test
    void parsesAllContractsAndVersionedEvaluationSets() throws Exception {
        Path projectRoot = Path.of("..").toAbsolutePath().normalize();
        JsonNode openApi = new ObjectMapper(new YAMLFactory()).readTree(
                projectRoot.resolve("contracts/openapi/mednav-api-v1.yaml").toFile());
        assertThat(openApi.path("info").path("version").asText()).isEqualTo("0.8.2");
        assertThat(openApi.path("paths").has("/api/users/me/sessions")).isTrue();
        assertThat(openApi.path("paths").has("/api/users/me/sessions/{sessionId}/messages")).isTrue();
        assertThat(openApi.path("paths").has("/api/users/me/sessions/{sessionId}/resume")).isTrue();
        assertThat(openApi.path("paths").has("/api/users/me/session-memories")).isTrue();
        assertThat(openApi.path("components")
                        .path("responses")
                        .has("MemoryCacheUnavailable"))
                .isTrue();

        ObjectMapper json = new ObjectMapper();
        Path schemaDirectory = projectRoot.resolve("contracts/json-schema");
        long schemaCount;
        try (var schemas = Files.list(schemaDirectory)) {
            schemaCount = schemas.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .peek(path -> parseJsonFile(json, path))
                    .count();
        }
        assertThat(schemaCount).isEqualTo(7);

        Set<String> caseIds = new HashSet<>();
        Map<String, Integer> casesByDataset = new TreeMap<>();
        Path setDirectory = projectRoot.resolve("evaluation/sets");
        try (var sets = Files.list(setDirectory)) {
            for (Path set : sets.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList()) {
                int cases = 0;
                try (var lines = Files.lines(set)) {
                    for (String line : lines.filter(value -> !value.isBlank()).toList()) {
                        JsonNode row = parseJsonLine(json, set, line);
                        assertThat(row.path("caseId").asText()).isNotBlank();
                        assertThat(row.path("module").asText()).isNotBlank();
                        assertThat(row.path("input").isObject()).isTrue();
                        assertThat(row.path("expected").isObject()).isTrue();
                        assertThat(row.path("tags").isArray()).isTrue();
                        assertThat(row.path("tags").isEmpty()).isFalse();
                        assertThat(row.path("datasetVersion").asText()).isNotBlank();
                        assertThat(caseIds.add(row.path("caseId").asText()))
                                .as("caseId must be globally unique: %s", row.path("caseId").asText())
                                .isTrue();
                        cases++;
                    }
                }
                casesByDataset.put(set.getFileName().toString(), cases);
            }
        }
        assertThat(casesByDataset).isEqualTo(Map.of(
                "m1-smoke-v0.jsonl", 5,
                "m2-auth-profile-v0.jsonl", 11,
                "m3-clinical-v0.jsonl", 12,
                "m4-cms-v0.jsonl", 13,
                "m5-location-route-v0.jsonl", 14,
                "m6-recommendation-v0.jsonl", 17,
                "m7-memory-v0.jsonl", 17,
                "m8-session-history-v0.jsonl", 6));
        assertThat(caseIds).hasSize(95);
    }

    private void parseJsonFile(ObjectMapper json, Path path) {
        try {
            JsonNode document = json.readTree(path.toFile());
            assertThat(document.path("$schema").asText()).isNotBlank();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON contract: " + path, exception);
        }
    }

    private JsonNode parseJsonLine(ObjectMapper json, Path path, String line) {
        try {
            return json.readTree(line);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid evaluation row in " + path, exception);
        }
    }
}
