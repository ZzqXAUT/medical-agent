package com.example.mednav.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Conservative hook for facts the user states explicitly. It intentionally recognizes a small,
 * auditable phrase set instead of guessing allergies, contraindications, or prior diagnoses.
 */
@Service
public class ExplicitMemoryFactExtractionService {

    public static final Set<String> CONSTRAINT_KEYS = Set.of(
            "drug_allergies",
            "food_allergies",
            "medication_contraindications",
            "dietary_restrictions");

    private static final List<Pattern> DISEASE_HISTORY = List.of(
            Pattern.compile("(?i)\\b(?:history of|previously diagnosed with|was diagnosed with)\\s+([^.;\\n]+)"),
            Pattern.compile("(?:既往患有|既往有|曾被诊断为|曾确诊为)\\s*([^，。；;\\n]+)"));
    private static final List<Pattern> DRUG_ALLERGIES = List.of(
            Pattern.compile("(?i)\\bdrug allerg(?:y|ies)\\s*(?::|to|include|includes)?\\s*([^.;\\n]+)"),
            Pattern.compile("药物过敏(?:包括|是|[:：])?\\s*([^，。；;\\n]+)"),
            Pattern.compile("对\\s*([^，。；;\\n]+?)\\s*药物过敏"));
    private static final List<Pattern> FOOD_ALLERGIES = List.of(
            Pattern.compile("(?i)\\bfood allerg(?:y|ies)\\s*(?::|to|include|includes)?\\s*([^.;\\n]+)"),
            Pattern.compile("食物过敏(?:包括|是|[:：])?\\s*([^，。；;\\n]+)"));
    private static final List<Pattern> MEDICATION_CONTRAINDICATIONS = List.of(
            Pattern.compile("(?i)\\bmedication contraindications?\\s*(?::|include|includes)?\\s*([^.;\\n]+)"),
            Pattern.compile("(?i)\\b(?:cannot|must not) take\\s+([^.;\\n]+)"),
            Pattern.compile("用药禁忌(?:包括|是|[:：])?\\s*([^，。；;\\n]+)"));
    private static final List<Pattern> DIETARY_RESTRICTIONS = List.of(
            Pattern.compile("(?i)\\bdietary restrictions?\\s*(?::|include|includes)?\\s*([^.;\\n]+)"),
            Pattern.compile("(?i)\\b(vegan|vegetarian|gluten-free|kosher|halal)\\b"),
            Pattern.compile("饮食限制(?:包括|是|[:：])?\\s*([^，。；;\\n]+)"),
            Pattern.compile("(素食主义者|素食|无麸质饮食)"));

    public ExplicitMemoryFacts extract(String message) {
        if (message == null || message.isBlank()) {
            return ExplicitMemoryFacts.empty();
        }
        List<String> diseases = extractValues(message, DISEASE_HISTORY);
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        putIfPresent(constraints, "drug_allergies", extractValues(message, DRUG_ALLERGIES));
        putIfPresent(constraints, "food_allergies", extractValues(message, FOOD_ALLERGIES));
        putIfPresent(
                constraints,
                "medication_contraindications",
                extractValues(message, MEDICATION_CONTRAINDICATIONS));
        putIfPresent(
                constraints,
                "dietary_restrictions",
                extractValues(message, DIETARY_RESTRICTIONS));
        return new ExplicitMemoryFacts(diseases, constraints);
    }

    private List<String> extractValues(String message, List<Pattern> patterns) {
        Set<String> values = new LinkedHashSet<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                for (String candidate : split(matcher.group(1))) {
                    String normalized = normalize(candidate);
                    if (!normalized.isBlank() && normalized.length() <= 100) {
                        values.add(normalized);
                    }
                }
            }
        }
        return values.stream().limit(10).toList();
    }

    private List<String> split(String captured) {
        String[] parts = captured.split("(?i)\\s*(?:,|、|\\band\\b|以及|和)\\s*");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                values.add(part);
            }
        }
        return values;
    }

    private String normalize(String value) {
        String result = value.strip().replaceAll("\\s+", " ");
        if (result.equals(result.toUpperCase(Locale.ROOT)) && result.length() > 4) {
            return result.toLowerCase(Locale.ROOT);
        }
        return result;
    }

    private void putIfPresent(
            Map<String, List<String>> target, String key, List<String> values) {
        if (!CONSTRAINT_KEYS.contains(key)) {
            throw new IllegalArgumentException("Unsupported core constraint key: " + key);
        }
        if (!values.isEmpty()) {
            target.put(key, values);
        }
    }
}
