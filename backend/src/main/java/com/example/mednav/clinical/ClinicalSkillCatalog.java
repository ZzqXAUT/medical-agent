package com.example.mednav.clinical;

import io.agentscope.core.skill.AgentSkill;
import java.util.List;
import org.springframework.stereotype.Component;

/** Versioned AgentScope skills that can be evaluated independently. */
@Component
public class ClinicalSkillCatalog {

    public List<AgentSkill> skills() {
        return List.of(
                skill(
                        "symptom-extraction-v1",
                        "SymptomExtractionSkill",
                        "Merge the current message into the previous SymptomCase. Preserve only user-provided facts. Extract chief complaint, duration, 0-10 severity, associated symptoms, red flags, and known facts. Never invent examination or test results."),
                skill(
                        "clinical-readiness-v1",
                        "ClinicalReadinessSkill",
                        "Judge whether the symptom facts support ASK_USER, READY, READY_LIMITED, or INSUFFICIENT_INFORMATION. ASK_USER contains exactly one unrepeated high-value question. Respect the three-question budget and 0.60 limited threshold."),
                skill(
                        "disease-inference-v1",
                        "DiseaseInferenceSkill",
                        "For READY states, return at most three unique preliminary disease hypotheses with bounded probability, severity, user-supplied supporting facts, limitations, and internal target specialties. These are navigation hypotheses, not diagnoses."));
    }

    private AgentSkill skill(String id, String name, String content) {
        return AgentSkill.builder()
                .name(name)
                .description(content)
                .putMetadata("id", id)
                .putMetadata("version", "1")
                .skillContent(content)
                .source("mednav-classpath")
                .build();
    }
}
