package com.example.mednav.clinical;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mednav.agent.MedicalNavigationAgentFactory;
import com.example.mednav.agent.AgentScopeStructuredResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class AgentScopeOpenAiClinicalReasoningEngineTest {

    @Test
    void reportsMissingModelConfigurationAsARecoverableModelError() {
        MedicalNavigationAgentFactory factory = mock(MedicalNavigationAgentFactory.class);
        when(factory.create()).thenThrow(new IllegalStateException("missing API configuration"));
        ObjectMapper objectMapper = new ObjectMapper();
        AgentScopeOpenAiClinicalReasoningEngine engine =
                new AgentScopeOpenAiClinicalReasoningEngine(
                        factory,
                        new AgentScopeStructuredResponseParser(objectMapper),
                        objectMapper,
                        "test-model",
                        "clinical-v1");
        ClinicalAssessmentRequest request = new ClinicalAssessmentRequest(
                "session", "user", "headache", null, List.of(), 0, Map.of());

        StepVerifier.create(engine.assess(request))
                .expectError(ClinicalModelUnavailableException.class)
                .verify();
    }
}
