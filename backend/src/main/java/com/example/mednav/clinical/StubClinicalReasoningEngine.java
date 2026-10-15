package com.example.mednav.clinical;

import com.example.mednav.i18n.DisplayLanguage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Deterministic clinical engine for local development and contract evaluation. */
@Component
@ConditionalOnProperty(name = "mednav.agent.mode", havingValue = "stub", matchIfMissing = true)
public class StubClinicalReasoningEngine implements ClinicalReasoningEngine {

    private static final Pattern DURATION = Pattern.compile(
            "(?i)(?:for\\s+\\d+\\s*(?:hours?|days?|weeks?|months?)|since\\s+[a-z0-9 -]+|"
                    + "(?:\\d+|[一二两三四五六七八九十几半]+)\\s*(?:小时|天|周|个月))");
    private static final Pattern SCORE = Pattern.compile("\\b(10|[0-9])\\s*/\\s*10\\b");
    private static final List<String> ASSOCIATED_TERMS = List.of(
            "vomiting", "nausea", "fever", "dizziness", "rash", "fatigue",
            "acid reflux", "heartburn", "呕吐", "恶心", "发热", "发烧", "头晕", "皮疹", "乏力",
            "反酸", "烧心");
    private static final List<String> RED_FLAG_TERMS = List.of(
            "chest pain", "difficulty breathing", "shortness of breath", "fainted",
            "one-sided weakness", "worst headache", "coughing blood", "blood in stool",
            "bloody stool", "black stool", "胸痛", "呼吸困难", "晕厥", "单侧无力", "剧烈头痛",
            "咳血", "便血", "大便有血", "血便", "黑便");

    private final int maxQuestions;
    private final double limitedThreshold;

    public StubClinicalReasoningEngine(
            @Value("${mednav.clinical.max-questions:3}") int maxQuestions,
            @Value("${mednav.clinical.readiness-threshold:0.60}") double limitedThreshold) {
        this.maxQuestions = maxQuestions;
        this.limitedThreshold = limitedThreshold;
    }

    @Override
    public Mono<ClinicalDecision> assess(ClinicalAssessmentRequest request) {
        return Mono.fromSupplier(() -> assessNow(request));
    }

    private ClinicalDecision assessNow(ClinicalAssessmentRequest request) {
        boolean chinese = DisplayLanguage.isChinese(request.responseLanguage());
        SymptomCase symptomCase = merge(
                request.previousSymptomCase(), request.currentMessage(), chinese);
        List<MissingInformation> missing = missingInformation(symptomCase, chinese);
        double confidence = readinessConfidence(symptomCase);
        List<DiseaseHypothesis> hypotheses = List.of();
        ClinicalReadiness decision;
        String question = null;

        if (!symptomCase.redFlags().isEmpty()) {
            confidence = Math.max(confidence, 0.85);
            decision = ClinicalReadiness.READY;
            hypotheses = inferDiseases(symptomCase, true, chinese);
        } else if (confidence >= 0.70 && symptomCase.chiefComplaint() != null) {
            decision = ClinicalReadiness.READY;
            hypotheses = inferDiseases(symptomCase, false, chinese);
        } else if (request.questionCount() >= maxQuestions) {
            if (confidence >= limitedThreshold && symptomCase.chiefComplaint() != null) {
                decision = ClinicalReadiness.READY_LIMITED;
                hypotheses = inferDiseases(symptomCase, false, chinese);
            } else {
                decision = ClinicalReadiness.INSUFFICIENT_INFORMATION;
            }
        } else {
            decision = ClinicalReadiness.ASK_USER;
            question = questionFor(missing, request.askedQuestions(), chinese);
        }

        return new ClinicalDecision(
                decision,
                missing,
                question,
                round(confidence),
                symptomCase,
                hypotheses);
    }

    private SymptomCase merge(SymptomCase previous, String currentMessage, boolean chinese) {
        String current = currentMessage == null ? "" : currentMessage.strip();
        String previousNarrative = previous == null ? null : previous.narrative();
        String narrative = previousNarrative == null || previousNarrative.isBlank()
                ? current
                : previousNarrative + " | " + current;
        String evidenceSource = narrative.toLowerCase(Locale.ROOT);

        String chief = previous == null ? null : previous.chiefComplaint();
        String recognized = recognizeChiefComplaint(evidenceSource);
        if (recognized != null) {
            chief = recognized;
        }

        String duration = previous == null ? null : previous.duration();
        Matcher durationMatcher = DURATION.matcher(narrative);
        if (durationMatcher.find()) {
            duration = durationMatcher.group().strip();
        }

        Integer severityScore = previous == null ? null : previous.severityScore();
        Matcher scoreMatcher = SCORE.matcher(narrative);
        if (scoreMatcher.find()) {
            severityScore = Integer.parseInt(scoreMatcher.group(1));
        } else if (containsAny(evidenceSource, List.of("severe", "very bad", "剧烈", "严重"))) {
            severityScore = 8;
        } else if (containsAny(evidenceSource, List.of("moderate", "中等", "一般"))) {
            severityScore = 5;
        } else if (containsAny(evidenceSource, List.of("mild", "轻微", "轻度"))) {
            severityScore = 2;
        }

        Set<String> associated = new LinkedHashSet<>();
        Set<String> redFlags = new LinkedHashSet<>();
        if (previous != null) {
            associated.addAll(previous.associatedSymptoms());
            redFlags.addAll(previous.redFlags());
        }
        ASSOCIATED_TERMS.stream().filter(evidenceSource::contains).forEach(associated::add);
        RED_FLAG_TERMS.stream().filter(evidenceSource::contains).forEach(redFlags::add);

        List<String> facts = new ArrayList<>();
        if (chief != null) {
            facts.add(chinese
                    ? "主要症状：" + localizeChief(chief)
                    : "Main symptom: " + chief);
        }
        if (duration != null) {
            facts.add(chinese ? "持续时间：" + duration : "Duration: " + duration);
        }
        if (severityScore != null) {
            facts.add(chinese
                    ? "症状强度：" + severityScore + "/10"
                    : "Symptom severity: " + severityScore + "/10");
        }
        if (!associated.isEmpty()) {
            facts.add((chinese ? "伴随症状：" : "Associated symptoms: ")
                    + associated.stream()
                            .map(item -> chinese ? localizeSymptomTerm(item) : item)
                            .collect(java.util.stream.Collectors.joining(chinese ? "、" : ", ")));
        }
        if (!redFlags.isEmpty()) {
            facts.add((chinese ? "需要重点关注的表现：" : "Important warning signs: ")
                    + redFlags.stream()
                            .map(item -> chinese ? localizeSymptomTerm(item) : item)
                            .collect(java.util.stream.Collectors.joining(chinese ? "、" : ", ")));
        }

        return new SymptomCase(
                narrative,
                chief,
                duration,
                severityScore,
                List.copyOf(associated),
                List.copyOf(redFlags),
                facts);
    }

    private String recognizeChiefComplaint(String message) {
        if (containsAny(message, List.of("chest pain", "胸痛"))) return "chest pain";
        if (containsAny(message, List.of("headache", "头痛"))) return "headache";
        if (containsAny(message, List.of("cough", "咳嗽"))) return "cough";
        if (containsAny(message, List.of(
                "abdominal pain", "stomach pain", "腹痛", "肚子痛", "肚子疼", "胃痛", "胃疼", "腹部疼"))) {
            return "abdominal pain";
        }
        if (containsAny(message, List.of("fever", "发热", "发烧"))) return "fever";
        if (containsAny(message, List.of("dizzy", "dizziness", "头晕"))) return "dizziness";
        return null;
    }

    private List<MissingInformation> missingInformation(
            SymptomCase symptomCase, boolean chinese) {
        List<MissingInformation> missing = new ArrayList<>();
        if (symptomCase.chiefComplaint() == null) {
            missing.add(new MissingInformation(
                    "chief_complaint",
                    chinese ? "需要明确主要症状" : "The main symptom is still needed",
                    1));
        }
        if (symptomCase.duration() == null) {
            missing.add(new MissingInformation(
                    "duration",
                    chinese ? "需要了解症状开始时间或持续时间" : "The start time or duration is still needed",
                    2));
        }
        if (symptomCase.severityScore() == null) {
            missing.add(new MissingInformation(
                    "severity",
                    chinese ? "需要了解症状强度" : "The symptom severity is still needed",
                    3));
        }
        if (symptomCase.associatedSymptoms().isEmpty() && symptomCase.redFlags().isEmpty()) {
            missing.add(new MissingInformation(
                    "associated_symptoms",
                    chinese ? "需要了解伴随症状或需要重点关注的表现" : "Associated symptoms or warning signs are still needed",
                    4));
        }
        return missing;
    }

    private double readinessConfidence(SymptomCase symptomCase) {
        double confidence = symptomCase.chiefComplaint() == null ? 0.05 : 0.40;
        if (symptomCase.duration() != null) confidence += 0.20;
        if (symptomCase.severityScore() != null) confidence += 0.15;
        if (!symptomCase.associatedSymptoms().isEmpty()) confidence += 0.15;
        if (!symptomCase.redFlags().isEmpty()) confidence += 0.20;
        return Math.min(confidence, 1.0);
    }

    private String questionFor(
            List<MissingInformation> missing, List<String> askedQuestions, boolean chinese) {
        List<String> candidates = new ArrayList<>();
        for (MissingInformation item : missing) {
            candidates.add(chinese
                    ? switch (item.field()) {
                        case "chief_complaint" -> "请描述这次最主要的不适或症状是什么？";
                        case "duration" -> "这个症状从什么时候开始，已经持续多久？";
                        case "severity" -> "目前症状有多严重，可以用 0 到 10 分表示吗？";
                        default -> "是否还有同时出现的症状或明显异常？";
                    }
                    : switch (item.field()) {
                        case "chief_complaint" -> "What is the main symptom or discomfort you are experiencing?";
                        case "duration" -> "When did this symptom start, and how long has it lasted?";
                        case "severity" -> "How severe is it now on a scale from 0 to 10?";
                        default -> "Are there any other symptoms or unusual changes occurring with it?";
                    });
        }
        if (candidates.isEmpty()) {
            candidates.add(chinese
                    ? "还有哪些与本次不适同时出现的症状？"
                    : "What other symptoms are occurring with this problem?");
        }
        Set<String> normalizedAsked = askedQuestions.stream()
                .map(question -> question.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return candidates.stream()
                .filter(question -> !normalizedAsked.contains(
                        question.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(chinese
                        ? "请补充一个此前未提到、但与你当前不适有关的新情况？"
                        : "Please add one new detail related to your current symptoms.");
    }

    private List<DiseaseHypothesis> inferDiseases(
            SymptomCase symptomCase, boolean redFlag, boolean chinese) {
        String chief = symptomCase.chiefComplaint();
        List<String> facts = symptomCase.knownFacts().isEmpty()
                ? List.of(chinese ? "用户描述了" + localizeChief(chief) : "The user reported " + chief)
                : symptomCase.knownFacts();
        List<String> limitations = missingInformation(symptomCase, chinese).stream()
                .map(MissingInformation::reason)
                .toList();
        DiseaseSeverity baseSeverity = severity(symptomCase, redFlag);

        if ("headache".equals(chief)) {
            return List.of(
                    hypothesis(chinese ? "偏头痛" : "Migraine", baseSeverity, 0.46, facts, limitations, "Neurology"),
                    hypothesis(chinese ? "紧张型头痛" : "Tension-type headache", DiseaseSeverity.MILD, 0.34, facts, limitations, "Internal Medicine"),
                    hypothesis(chinese ? "脱水相关性头痛" : "Dehydration-related headache", DiseaseSeverity.MILD, 0.20, facts, limitations, "Family Practice"));
        }
        if ("cough".equals(chief)) {
            return List.of(
                    hypothesis(chinese ? "上呼吸道感染" : "Upper respiratory infection", DiseaseSeverity.MILD, 0.48, facts, limitations, "Family Practice"),
                    hypothesis(chinese ? "急性支气管炎" : "Acute bronchitis", DiseaseSeverity.MODERATE, 0.31, facts, limitations, "Pulmonary Disease"),
                    hypothesis(chinese ? "肺炎" : "Pneumonia", DiseaseSeverity.SEVERE, 0.21, facts, limitations, "Pulmonary Disease"));
        }
        if ("chest pain".equals(chief)) {
            return List.of(
                    hypothesis(chinese ? "急性冠状动脉综合征" : "Acute coronary syndrome", DiseaseSeverity.SEVERE, 0.40, facts, limitations, "Cardiovascular Disease"),
                    hypothesis(chinese ? "肌肉骨骼性胸痛" : "Musculoskeletal chest pain", DiseaseSeverity.MODERATE, 0.35, facts, limitations, "Internal Medicine"),
                    hypothesis(chinese ? "胃食管反流病" : "Gastroesophageal reflux disease", DiseaseSeverity.MILD, 0.25, facts, limitations, "Gastroenterology"));
        }
        if ("abdominal pain".equals(chief)) {
            return List.of(
                    hypothesis(chinese ? "胃肠炎" : "Gastroenteritis", DiseaseSeverity.MODERATE, 0.42, facts, limitations, "Gastroenterology"),
                    hypothesis(chinese ? "消化不良" : "Dyspepsia", DiseaseSeverity.MILD, 0.33, facts, limitations, "Internal Medicine"),
                    hypothesis(chinese ? "阑尾炎" : "Appendicitis", DiseaseSeverity.SEVERE, 0.25, facts, limitations, "General Surgery"));
        }
        if ("fever".equals(chief)) {
            return List.of(
                    hypothesis(chinese ? "病毒感染" : "Viral infection", DiseaseSeverity.MILD, 0.50, facts, limitations, "Family Practice"),
                    hypothesis(chinese ? "细菌感染" : "Bacterial infection", DiseaseSeverity.MODERATE, 0.30, facts, limitations, "Internal Medicine"),
                    hypothesis(chinese ? "流行性感冒" : "Influenza", DiseaseSeverity.MODERATE, 0.20, facts, limitations, "Family Practice"));
        }
        return List.of(
                hypothesis(chinese ? "尚未明确的症状相关情况" : "Undifferentiated symptom condition", baseSeverity, 0.45, facts, limitations, "Internal Medicine"));
    }

    private String localizeChief(String chief) {
        return switch (chief) {
            case "chest pain" -> "胸痛";
            case "headache" -> "头痛";
            case "cough" -> "咳嗽";
            case "abdominal pain" -> "腹痛";
            case "fever" -> "发热";
            case "dizziness" -> "头晕";
            default -> "当前不适";
        };
    }

    private String localizeSymptomTerm(String term) {
        return switch (term) {
            case "vomiting" -> "呕吐";
            case "nausea" -> "恶心";
            case "fever" -> "发热";
            case "dizziness" -> "头晕";
            case "rash" -> "皮疹";
            case "fatigue" -> "乏力";
            case "acid reflux" -> "反酸";
            case "heartburn" -> "烧心";
            case "chest pain" -> "胸痛";
            case "difficulty breathing", "shortness of breath" -> "呼吸困难";
            case "fainted" -> "晕厥";
            case "one-sided weakness" -> "单侧无力";
            case "worst headache" -> "剧烈头痛";
            case "coughing blood" -> "咳血";
            case "blood in stool", "bloody stool" -> "便血";
            case "black stool" -> "黑便";
            default -> term;
        };
    }

    private DiseaseHypothesis hypothesis(
            String name,
            DiseaseSeverity severity,
            double probability,
            List<String> facts,
            List<String> limitations,
            String specialty) {
        return new DiseaseHypothesis(
                name,
                severity,
                probability,
                facts,
                limitations,
                List.of(specialty));
    }

    private DiseaseSeverity severity(SymptomCase symptomCase, boolean redFlag) {
        if (redFlag || (symptomCase.severityScore() != null && symptomCase.severityScore() >= 8)) {
            return DiseaseSeverity.SEVERE;
        }
        if (symptomCase.severityScore() != null && symptomCase.severityScore() >= 4) {
            return DiseaseSeverity.MODERATE;
        }
        return DiseaseSeverity.MILD;
    }

    private boolean containsAny(String text, List<String> values) {
        return values.stream().anyMatch(text::contains);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
