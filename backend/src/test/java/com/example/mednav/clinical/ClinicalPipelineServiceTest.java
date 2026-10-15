package com.example.mednav.clinical;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ClinicalPipelineServiceTest {

    @Test
    void asksAConcreteQuestionWhenThePrimaryModelIsUnavailable() {
        ClinicalReasoningEngine unavailable = request -> Mono.error(
                new ClinicalModelUnavailableException("test failure"));
        ClinicalPipelineService service = new ClinicalPipelineService(
                unavailable, new ClinicalDecisionGate(3, 0.60), 3, 0.60);

        ClinicalDecision result = service.assess(new ClinicalAssessmentRequest(
                        "session",
                        "user",
                        "最近这两天有点肚子疼",
                        null,
                        List.of(),
                        0,
                        Map.of()))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(ClinicalReadiness.ASK_USER);
        assertThat(result.proposedQuestion()).contains("0 到 10");
    }
}
