package com.example.mednav.clinical;

import com.example.mednav.agent.MedicalNavigationAgentFactory;
import com.example.mednav.agent.AgentScopeStructuredResponseParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** AgentScope/OpenAI implementation. Configuration is validated lazily per Run. */
@Component
@ConditionalOnProperty(name = "mednav.agent.mode", havingValue = "openai")
public class AgentScopeOpenAiClinicalReasoningEngine implements ClinicalReasoningEngine {

    private static final Logger log =
            LoggerFactory.getLogger(AgentScopeOpenAiClinicalReasoningEngine.class);

    private final MedicalNavigationAgentFactory agentFactory;
    private final AgentScopeStructuredResponseParser responseParser;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final String promptVersion;

    public AgentScopeOpenAiClinicalReasoningEngine(
            MedicalNavigationAgentFactory agentFactory,
            AgentScopeStructuredResponseParser responseParser,
            ObjectMapper objectMapper,
            @Value("${mednav.model.primary:unconfigured}") String modelName,
            @Value("${mednav.clinical.prompt-version:clinical-v1}") String promptVersion) {
        this.agentFactory = agentFactory;
        this.responseParser = responseParser;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
    }

    @Override
    public Mono<ClinicalDecision> assess(ClinicalAssessmentRequest request) {
        final String input;
        try {
            input = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            return Mono.error(new ClinicalValidationException(
                    "Unable to serialize the clinical assessment request"));
        }

        return Mono.using(
                        agentFactory::create,
                        agent -> agent.call(
                                        input,
                                        ClinicalDecision.class,
                                        RuntimeContext.builder()
                                                .sessionId(request.sessionId())
                                                .userId(request.userId())
                                                .build())
                                .map(message -> {
                                    ChatUsage usage = message.getChatUsage();
                                    if (usage == null) {
                                        log.info(
                                                "clinical_model_completed sessionId={} model={} promptVersion={} tokenUsage=unavailable",
                                                request.sessionId(),
                                                modelName,
                                                promptVersion);
                                    } else {
                                        log.info(
                                                "clinical_model_completed sessionId={} model={} promptVersion={} inputTokens={} outputTokens={} cachedTokens={} latencySeconds={}",
                                                request.sessionId(),
                                                modelName,
                                                promptVersion,
                                                usage.getInputTokens(),
                                                usage.getOutputTokens(),
                                                usage.getCachedTokens(),
                                                usage.getTime());
                                    }
                                    try {
                                        ClinicalDecision decision = responseParser.parse(
                                                message, ClinicalDecision.class);
                                        if (decision == null) {
                                            throw new ClinicalValidationException(
                                                    "Clinical model response contained empty structured data");
                                        }
                                        return decision;
                                    } catch (ClinicalValidationException exception) {
                                        throw exception;
                                    } catch (RuntimeException exception) {
                                        log.warn(
                                                "clinical_model_deserialization_failed sessionId={} structuredKeys={} contentBlocks={} textLength={} errorType={} errorMessage={}",
                                                request.sessionId(),
                                                message.hasStructuredData()
                                                        ? message.getStructuredData(true).keySet()
                                                        : java.util.Set.of(),
                                                message.getContent().stream()
                                                        .map(block -> block.getClass().getSimpleName())
                                                        .toList(),
                                                message.getTextContent() == null
                                                        ? 0
                                                        : message.getTextContent().length(),
                                                exception.getClass().getSimpleName(),
                                                exception.getMessage());
                                        throw new ClinicalValidationException(
                                                "Clinical model structured response could not be deserialized");
                                    }
                                }),
                        ReActAgent::close)
                .onErrorMap(
                        error -> !(error instanceof ClinicalValidationException),
                        error -> new ClinicalModelUnavailableException(
                                "Clinical model request failed", error));
    }
}
