package com.example.mednav.clinical;

import com.example.mednav.i18n.DisplayLanguage;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClinicalDecisionGate {

    private final int maxQuestions;
    private final double limitedThreshold;

    public ClinicalDecisionGate(
            @Value("${mednav.clinical.max-questions:3}") int maxQuestions,
            @Value("${mednav.clinical.readiness-threshold:0.60}") double limitedThreshold) {
        this.maxQuestions = maxQuestions;
        this.limitedThreshold = limitedThreshold;
    }

    public ClinicalDecision validateAndNormalize(
            ClinicalDecision candidate, ClinicalAssessmentRequest request) {
        if (candidate == null || candidate.decision() == null || candidate.symptomCase() == null) {
            throw new ClinicalValidationException("Clinical output is missing required fields");
        }
        if (!Double.isFinite(candidate.readinessConfidence())
                || candidate.readinessConfidence() < 0
                || candidate.readinessConfidence() > 1) {
            throw new ClinicalValidationException("readinessConfidence must be between 0 and 1");
        }
        validateHypotheses(candidate.hypotheses());
        if (candidate.symptomCase().severityScore() != null
                && (candidate.symptomCase().severityScore() < 0
                        || candidate.symptomCase().severityScore() > 10)) {
            throw new ClinicalValidationException("severityScore must be between 0 and 10");
        }

        ClinicalDecision normalizedCandidate = candidate;
        if (request.questionCount() >= maxQuestions
                && candidate.decision() == ClinicalReadiness.ASK_USER) {
            normalizedCandidate = candidate.readinessConfidence() >= limitedThreshold
                    ? candidate.withDecision(
                            ClinicalReadiness.READY_LIMITED,
                            null,
                            candidate.hypotheses())
                    : candidate.withDecision(
                            ClinicalReadiness.INSUFFICIENT_INFORMATION,
                            null,
                            List.of());
        }

        if (normalizedCandidate.decision() == ClinicalReadiness.ASK_USER) {
            if (normalizedCandidate.proposedQuestion() == null
                    || normalizedCandidate.proposedQuestion().isBlank()) {
                throw new ClinicalValidationException("ASK_USER requires one proposed question");
            }
            long questionMarks = normalizedCandidate.proposedQuestion().chars()
                    .filter(ch -> ch == '?' || ch == '？')
                    .count();
            if (questionMarks > 1) {
                throw new ClinicalValidationException("Clarification must contain one concise question");
            }
            String normalized = normalize(normalizedCandidate.proposedQuestion());
            boolean repeated = request.askedQuestions().stream()
                    .map(this::normalize)
                    .anyMatch(normalized::equals);
            if (repeated) {
                throw new ClinicalValidationException("Clarification question repeats an earlier question");
            }
        }

        if (normalizedCandidate.decision() == ClinicalReadiness.READY
                || normalizedCandidate.decision() == ClinicalReadiness.READY_LIMITED) {
            if (normalizedCandidate.symptomCase().chiefComplaint() == null
                    || normalizedCandidate.symptomCase().chiefComplaint().isBlank()
                    || normalizedCandidate.symptomCase().knownFacts().isEmpty()) {
                return normalizedCandidate.withDecision(
                        ClinicalReadiness.INSUFFICIENT_INFORMATION, null, List.of());
            }
            if (normalizedCandidate.hypotheses().isEmpty()) {
                throw new ClinicalValidationException("A READY decision requires disease hypotheses");
            }
        }

        if (normalizedCandidate.decision() == ClinicalReadiness.INSUFFICIENT_INFORMATION
                && !normalizedCandidate.hypotheses().isEmpty()) {
            normalizedCandidate = normalizedCandidate.withDecision(
                    ClinicalReadiness.INSUFFICIENT_INFORMATION, null, List.of());
        }
        validateDisplayLanguage(normalizedCandidate, request.responseLanguage());
        return normalizedCandidate;
    }

    private void validateDisplayLanguage(
            ClinicalDecision candidate, String responseLanguage) {
        List<String> patientFacing = new java.util.ArrayList<>();
        if (candidate.proposedQuestion() != null) {
            patientFacing.add(candidate.proposedQuestion());
        }
        candidate.hypotheses().forEach(hypothesis -> {
            patientFacing.add(hypothesis.name());
            patientFacing.addAll(hypothesis.supportingFacts());
            patientFacing.addAll(hypothesis.limitations());
        });
        if (patientFacing.isEmpty()) return;
        String combined = String.join(" ", patientFacing);
        boolean containsChinese = combined.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                        == Character.UnicodeScript.HAN);
        if (DisplayLanguage.isChinese(responseLanguage) && !containsChinese) {
            throw new ClinicalValidationException(
                    "Patient-facing clinical text must match zh-CN");
        }
        if (!DisplayLanguage.isChinese(responseLanguage) && containsChinese) {
            throw new ClinicalValidationException(
                    "Patient-facing clinical text must match en-US");
        }
    }

    private void validateHypotheses(List<DiseaseHypothesis> hypotheses) {
        if (hypotheses.size() > 3) {
            throw new ClinicalValidationException("At most three disease hypotheses are allowed");
        }
        Set<String> names = new HashSet<>();
        for (DiseaseHypothesis hypothesis : hypotheses) {
            if (hypothesis.name() == null || hypothesis.name().isBlank()) {
                throw new ClinicalValidationException("Disease hypothesis name is required");
            }
            if (!names.add(hypothesis.name().strip().toLowerCase(Locale.ROOT))) {
                throw new ClinicalValidationException("Disease hypotheses must be unique");
            }
            if (!Double.isFinite(hypothesis.estimatedProbability())
                    || hypothesis.estimatedProbability() < 0
                    || hypothesis.estimatedProbability() > 1) {
                throw new ClinicalValidationException("Disease probability must be between 0 and 1");
            }
            if (hypothesis.severity() == null
                    || hypothesis.supportingFacts().isEmpty()
                    || hypothesis.targetSpecialties().isEmpty()) {
                throw new ClinicalValidationException(
                        "Each disease hypothesis requires severity, supporting facts, and internal specialties");
            }
        }
    }

    private String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
