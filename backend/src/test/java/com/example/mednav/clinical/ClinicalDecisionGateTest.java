package com.example.mednav.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClinicalDecisionGateTest {

    private final ClinicalDecisionGate gate = new ClinicalDecisionGate(3, 0.60);

    @Test
    void rejectsARepeatedClarificationQuestion() {
        ClinicalDecision candidate = new ClinicalDecision(
                ClinicalReadiness.ASK_USER,
                List.of(new MissingInformation("duration", "missing", 1)),
                "How long has it lasted?",
                0.4,
                symptomCase(),
                List.of());
        ClinicalAssessmentRequest request = new ClinicalAssessmentRequest(
                "s",
                "u",
                "still unsure",
                null,
                List.of("  HOW LONG has it lasted? "),
                1,
                Map.of());

        assertThatThrownBy(() -> gate.validateAndNormalize(candidate, request))
                .isInstanceOf(ClinicalValidationException.class)
                .hasMessageContaining("repeats");
    }

    @Test
    void enforcesTheThreeQuestionBudgetWithoutAnotherModelDecision() {
        ClinicalDecision candidate = new ClinicalDecision(
                ClinicalReadiness.ASK_USER,
                List.of(),
                "Anything else?",
                0.3,
                symptomCase(),
                List.of());
        ClinicalAssessmentRequest request = new ClinicalAssessmentRequest(
                "s", "u", "unknown", null, List.of(), 3, Map.of());

        ClinicalDecision result = gate.validateAndNormalize(candidate, request);

        assertThat(result.decision()).isEqualTo(ClinicalReadiness.INSUFFICIENT_INFORMATION);
        assertThat(result.proposedQuestion()).isNull();
        assertThat(result.hypotheses()).isEmpty();
    }

    @Test
    void rejectsPatientFacingTextThatDoesNotMatchTheRequestedLanguage() {
        ClinicalDecision candidate = new ClinicalDecision(
                ClinicalReadiness.ASK_USER,
                List.of(),
                "How long has it lasted?",
                0.4,
                symptomCase(),
                List.of());
        ClinicalAssessmentRequest request = new ClinicalAssessmentRequest(
                "s",
                "u",
                "头痛",
                null,
                List.of(),
                0,
                Map.of(),
                List.of(),
                Map.of(),
                List.of(),
                "zh-CN");

        assertThatThrownBy(() -> gate.validateAndNormalize(candidate, request))
                .isInstanceOf(ClinicalValidationException.class)
                .hasMessageContaining("zh-CN");
    }

    private SymptomCase symptomCase() {
        return new SymptomCase("headache", "headache", null, null, List.of(), List.of(), List.of("headache"));
    }
}
