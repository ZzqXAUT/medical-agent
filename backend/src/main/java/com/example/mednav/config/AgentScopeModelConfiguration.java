package com.example.mednav.config;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

/** Lazy model beans keep the application bootable until a model is actually requested. */
@Configuration
@ConditionalOnProperty(name = "mednav.agent.mode", havingValue = "openai")
public class AgentScopeModelConfiguration {

    @Bean("primaryModel")
    @Lazy
    OpenAIChatModel primaryModel(ModelSettings settings) {
        return buildModel(settings.primaryModel(), "PRIMARY_MODEL", settings);
    }

    @Bean("summaryModel")
    @Lazy
    OpenAIChatModel summaryModel(ModelSettings settings) {
        return buildModel(settings.summaryModel(), "SUMMARY_MODEL", settings);
    }

    @Bean("semanticVerificationModel")
    @Lazy
    OpenAIChatModel semanticVerificationModel(ModelSettings settings) {
        return buildModel(settings.verificationModel(), "VERIFICATION_MODEL", settings);
    }

    @Bean
    ModelSettings modelSettings(
            @Value("${mednav.model.api-key:}") String apiKey,
            @Value("${mednav.model.base-url:}") String baseUrl,
            @Value("${mednav.model.primary:}") String primaryModel,
            @Value("${mednav.model.summary:}") String summaryModel,
            @Value("${mednav.model.semantic-verification:}") String verificationModel,
            @Value("${mednav.model.reasoning-effort:none}") String reasoningEffort,
            @Value("${mednav.model.native-structured-output:false}") boolean nativeStructuredOutput,
            @Value("${mednav.clinical.model-timeout:45s}") Duration timeout,
            @Value("${mednav.clinical.model-max-attempts:2}") int maxAttempts,
            @Value("${mednav.clinical.temperature:0.2}") double temperature,
            @Value("${mednav.clinical.max-completion-tokens:1800}") int maxCompletionTokens) {
        return new ModelSettings(
                apiKey,
                baseUrl,
                primaryModel,
                summaryModel,
                verificationModel,
                reasoningEffort,
                nativeStructuredOutput,
                timeout,
                maxAttempts,
                temperature,
                maxCompletionTokens);
    }

    private OpenAIChatModel buildModel(
            String modelName, String settingName, ModelSettings settings) {
        if (!StringUtils.hasText(settings.apiKey()) || !StringUtils.hasText(modelName)) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY and " + settingName + " are required for this model");
        }
        GenerateOptions options = settings.generateOptions(
                settings.temperature(), settings.maxCompletionTokens());
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(settings.apiKey())
                .modelName(modelName)
                .stream(false)
                .nativeStructuredOutput(settings.nativeStructuredOutput())
                .generateOptions(options);
        if (StringUtils.hasText(settings.baseUrl())) {
            builder.baseUrl(settings.baseUrl());
        }
        return builder.build();
    }

    public record ModelSettings(
            String apiKey,
            String baseUrl,
            String primaryModel,
            String summaryModel,
            String verificationModel,
            String reasoningEffort,
            boolean nativeStructuredOutput,
            Duration timeout,
            int maxAttempts,
            double temperature,
            int maxCompletionTokens) {

        public ExecutionConfig executionConfig() {
            return ExecutionConfig.builder()
                    .timeout(timeout)
                    .maxAttempts(maxAttempts)
                    .initialBackoff(Duration.ofMillis(500))
                    .maxBackoff(Duration.ofSeconds(3))
                    .backoffMultiplier(2.0)
                    .build();
        }

        public GenerateOptions generateOptions(double requestTemperature, int requestMaxTokens) {
            return GenerateOptions.builder()
                    .temperature(requestTemperature)
                    .maxCompletionTokens(requestMaxTokens)
                    .reasoningEffort(reasoningEffort)
                    .executionConfig(executionConfig())
                    .build();
        }
    }
}
