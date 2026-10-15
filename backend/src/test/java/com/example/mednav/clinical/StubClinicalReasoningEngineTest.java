package com.example.mednav.clinical;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StubClinicalReasoningEngineTest {

    private final StubClinicalReasoningEngine engine = new StubClinicalReasoningEngine(3, 0.60);

    @Test
    void asksOneQuestionWhenTheInitialDescriptionIsIncomplete() {
        ClinicalDecision result = engine.assess(request("I have a headache", null, 0)).block();

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(ClinicalReadiness.ASK_USER);
        assertThat(result.proposedQuestion()).contains("how long");
        assertThat(result.hypotheses()).isEmpty();
    }

    @Test
    void becomesReadyWhenTheUserProvidesEnoughSymptomFacts() {
        ClinicalDecision result = engine.assess(request(
                        "Severe headache for 2 hours, 8/10, with vomiting", null, 0))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(ClinicalReadiness.READY);
        assertThat(result.hypotheses()).hasSize(3);
        assertThat(result.hypotheses()).allSatisfy(hypothesis -> {
            assertThat(hypothesis.supportingFacts()).isNotEmpty();
            assertThat(hypothesis.targetSpecialties()).isNotEmpty();
        });
    }

    @Test
    void degradesToLimitedHypothesesAfterThreeQuestionsAtThreshold() {
        ClinicalDecision result = engine.assess(request(
                        "headache for 2 days", null, 3))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(ClinicalReadiness.READY_LIMITED);
        assertThat(result.readinessConfidence()).isEqualTo(0.60);
        assertThat(result.hypotheses()).isNotEmpty();
    }

    @Test
    void returnsNoHypothesesWhenStillInsufficientAfterThreeQuestions() {
        ClinicalDecision result = engine.assess(request("I do not know", null, 3)).block();

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(ClinicalReadiness.INSUFFICIENT_INFORMATION);
        assertThat(result.hypotheses()).isEmpty();
    }

    @Test
    void choosesANewMissingFieldInsteadOfRepeatingAQuestion() {
        ClinicalDecision first = engine.assess(request("I have a headache", null, 0)).block();
        assertThat(first).isNotNull();
        ClinicalAssessmentRequest followUp = new ClinicalAssessmentRequest(
                "session",
                "user",
                "I do not know",
                first.symptomCase(),
                List.of(first.proposedQuestion()),
                1,
                Map.of());

        ClinicalDecision second = engine.assess(followUp).block();

        assertThat(second).isNotNull();
        assertThat(second.decision()).isEqualTo(ClinicalReadiness.ASK_USER);
        assertThat(second.proposedQuestion()).isNotEqualTo(first.proposedQuestion());
        assertThat(second.proposedQuestion()).contains("0 to 10");
    }

    @Test
    void understandsChineseAbdominalSymptomsAndEscalatesReportedBloodInStool() {
        ClinicalDecision first = engine.assess(request(
                        "最近这两天有点肚子疼", null, 0))
                .block();

        assertThat(first).isNotNull();
        assertThat(first.decision()).isEqualTo(ClinicalReadiness.ASK_USER);
        assertThat(first.symptomCase().chiefComplaint()).isEqualTo("abdominal pain");
        assertThat(first.symptomCase().duration()).contains("两天");
        assertThat(first.proposedQuestion()).contains("0 到 10");

        ClinicalAssessmentRequest followUp = new ClinicalAssessmentRequest(
                "session",
                "user",
                "除此之外胃部也有时候比较疼，吃完饭之后会反酸，大便有血",
                first.symptomCase(),
                List.of(first.proposedQuestion()),
                1,
                Map.of());
        ClinicalDecision second = engine.assess(followUp).block();

        assertThat(second).isNotNull();
        assertThat(second.decision()).isEqualTo(ClinicalReadiness.READY);
        assertThat(second.symptomCase().associatedSymptoms()).contains("反酸");
        assertThat(second.symptomCase().redFlags()).contains("大便有血");
        assertThat(second.hypotheses()).isNotEmpty();
        assertThat(second.hypotheses())
                .extracting(DiseaseHypothesis::name)
                .containsExactly("胃肠炎", "消化不良", "阑尾炎");
        assertThat(second.hypotheses())
                .flatExtracting(DiseaseHypothesis::supportingFacts)
                .allSatisfy(fact -> assertThat(fact).doesNotContain("Main symptom", "Duration"));
    }

    private ClinicalAssessmentRequest request(
            String message, SymptomCase previous, int questionCount) {
        return new ClinicalAssessmentRequest(
                "session", "user", message, previous, List.of(), questionCount, Map.of());
    }
}
