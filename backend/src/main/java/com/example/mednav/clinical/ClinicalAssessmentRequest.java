package com.example.mednav.clinical;

import com.example.mednav.memory.SessionMemoryRecord;
import java.util.List;
import java.util.Map;

public record ClinicalAssessmentRequest(
        String sessionId,
        String userId,
        String currentMessage,
        SymptomCase previousSymptomCase,
        List<String> askedQuestions,
        int questionCount,
        Map<String, Object> effectiveProfile,
        List<String> effectiveDiseaseHistory,
        Map<String, Object> effectiveCoreConstraints,
        List<SessionMemoryRecord> historicalSessions,
        String responseLanguage) {

    public ClinicalAssessmentRequest {
        askedQuestions = askedQuestions == null ? List.of() : List.copyOf(askedQuestions);
        effectiveProfile = effectiveProfile == null ? Map.of() : Map.copyOf(effectiveProfile);
        effectiveDiseaseHistory = effectiveDiseaseHistory == null
                ? List.of()
                : List.copyOf(effectiveDiseaseHistory);
        effectiveCoreConstraints = effectiveCoreConstraints == null
                ? Map.of()
                : Map.copyOf(effectiveCoreConstraints);
        historicalSessions = historicalSessions == null
                ? List.of()
                : List.copyOf(historicalSessions);
        responseLanguage = com.example.mednav.i18n.DisplayLanguage.normalize(responseLanguage);
    }

    public ClinicalAssessmentRequest(
            String sessionId,
            String userId,
            String currentMessage,
            SymptomCase previousSymptomCase,
            List<String> askedQuestions,
            int questionCount,
            Map<String, Object> effectiveProfile,
            List<String> effectiveDiseaseHistory,
            Map<String, Object> effectiveCoreConstraints,
            List<SessionMemoryRecord> historicalSessions) {
        this(
                sessionId,
                userId,
                currentMessage,
                previousSymptomCase,
                askedQuestions,
                questionCount,
                effectiveProfile,
                effectiveDiseaseHistory,
                effectiveCoreConstraints,
                historicalSessions,
                com.example.mednav.i18n.DisplayLanguage.resolve(currentMessage, null));
    }

    public ClinicalAssessmentRequest(
            String sessionId,
            String userId,
            String currentMessage,
            SymptomCase previousSymptomCase,
            List<String> askedQuestions,
            int questionCount,
            Map<String, Object> effectiveProfile) {
        this(
                sessionId,
                userId,
                currentMessage,
                previousSymptomCase,
                askedQuestions,
                questionCount,
                effectiveProfile,
                List.of(),
                Map.of(),
                List.of(),
                com.example.mednav.i18n.DisplayLanguage.resolve(currentMessage, null));
    }
}
