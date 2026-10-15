package com.example.mednav.memory;

import com.example.mednav.config.AgentScopeModelConfiguration.ModelSettings;
import com.example.mednav.agent.AgentScopeStructuredResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Stateless one-shot SessionSummarySkill backed by the configured summary model. */
@Component
@ConditionalOnProperty(name = "mednav.agent.mode", havingValue = "openai")
public class AgentScopeSessionSummaryGenerator implements SessionSummaryGenerator {

    private static final Logger log =
            LoggerFactory.getLogger(AgentScopeSessionSummaryGenerator.class);

    private final ObjectProvider<OpenAIChatModel> model;
    private final ModelSettings settings;
    private final ObjectMapper objectMapper;
    private final AgentScopeStructuredResponseParser responseParser;
    private final String systemPrompt;
    private final String modelName;

    public AgentScopeSessionSummaryGenerator(
            @Qualifier("summaryModel") ObjectProvider<OpenAIChatModel> model,
            ObjectProvider<ModelSettings> settings,
            ObjectMapper objectMapper,
            AgentScopeStructuredResponseParser responseParser,
            @Value("classpath:prompts/session-summary-system-v1.txt") Resource prompt,
            @Value("${mednav.model.summary:unconfigured}") String modelName) {
        this.model = model;
        this.settings = settings.getIfAvailable();
        this.objectMapper = objectMapper;
        this.responseParser = responseParser;
        this.modelName = modelName;
        try {
            this.systemPrompt = prompt.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load Session summary prompt", exception);
        }
    }

    @Override
    public Mono<String> generate(SessionWorkingMemory memory) {
        if (settings == null) {
            return Mono.error(new IllegalStateException("Summary model is unavailable"));
        }
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(memory))
                .flatMap(input -> Mono.using(
                        this::createAgent,
                        agent -> agent.call(
                                        input,
                                        SessionSummaryResult.class,
                                        RuntimeContext.builder()
                                                .sessionId(memory.sessionId())
                                                .userId(memory.userId())
                                                .build())
                                .map(message -> {
                                    ChatUsage usage = message.getChatUsage();
                                    log.info(
                                            "session_summary_model_completed sessionId={} model={} inputTokens={} outputTokens={}",
                                            memory.sessionId(),
                                            modelName,
                                            usage == null ? "unavailable" : usage.getInputTokens(),
                                            usage == null ? "unavailable" : usage.getOutputTokens());
                                    SessionSummaryResult result = responseParser.parse(
                                            message, SessionSummaryResult.class);
                                    if (result == null
                                            || result.summary() == null
                                            || result.summary().isBlank()) {
                                        throw new IllegalStateException("Summary model returned an empty result");
                                    }
                                    return result.summary().strip();
                                }),
                        ReActAgent::close));
    }

    private ReActAgent createAgent() {
        GenerateOptions options = settings.generateOptions(
                0.0, Math.min(settings.maxCompletionTokens(), 500));
        return ReActAgent.builder()
                .name("session-summary-skill")
                .description("Summarizes one medical navigation Session without creating facts")
                .sysPrompt(systemPrompt)
                .model(model.getObject())
                .generateOptions(options)
                .modelExecutionConfig(settings.executionConfig())
                .maxIters(1)
                .build();
    }
}
