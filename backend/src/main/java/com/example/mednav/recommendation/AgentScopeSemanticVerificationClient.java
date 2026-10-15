package com.example.mednav.recommendation;

import com.example.mednav.config.AgentScopeModelConfiguration.ModelSettings;
import com.example.mednav.agent.AgentScopeStructuredResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Conditional semantic checker. It has no tools, memory, autonomous loop, or final-pass authority. */
@Component
@ConditionalOnProperty(name = "mednav.agent.mode", havingValue = "openai")
public class AgentScopeSemanticVerificationClient implements SemanticVerificationClient {

    private final ObjectProvider<OpenAIChatModel> model;
    private final ModelSettings settings;
    private final ObjectMapper objectMapper;
    private final AgentScopeStructuredResponseParser responseParser;
    private final String systemPrompt;

    public AgentScopeSemanticVerificationClient(
            @Qualifier("semanticVerificationModel") ObjectProvider<OpenAIChatModel> model,
            ObjectProvider<ModelSettings> settings,
            ObjectMapper objectMapper,
            AgentScopeStructuredResponseParser responseParser,
            @Value("classpath:prompts/semantic-verification-system-v1.txt") Resource prompt) {
        this.model = model;
        this.settings = settings.getIfAvailable();
        this.objectMapper = objectMapper;
        this.responseParser = responseParser;
        try {
            this.systemPrompt = prompt.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load semantic verification prompt", exception);
        }
    }

    @Override
    public Mono<SemanticVerificationReport> verify(NavigationRecommendationResult result) {
        if (settings == null) {
            return Mono.error(new IllegalStateException("Semantic verification model is unavailable"));
        }
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(result))
                .flatMap(input -> Mono.using(
                        this::createAgent,
                        agent -> agent.call(
                                        input,
                                        SemanticVerificationReport.class,
                                        RuntimeContext.builder()
                                                .sessionId(result.conversationId().toString())
                                                .build())
                                .map(message -> responseParser.parse(
                                        message, SemanticVerificationReport.class)),
                        ReActAgent::close));
    }

    private ReActAgent createAgent() {
        GenerateOptions options = settings.generateOptions(
                0.0, Math.min(settings.maxCompletionTokens(), 1200));
        return ReActAgent.builder()
                .name("recommendation-semantic-verifier")
                .description("Finds evidence-to-claim and cross-layer semantic inconsistencies")
                .sysPrompt(systemPrompt)
                .model(model.getObject())
                .generateOptions(options)
                .modelExecutionConfig(settings.executionConfig())
                .maxIters(1)
                .build();
    }
}
