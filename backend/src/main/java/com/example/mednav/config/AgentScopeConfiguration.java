package com.example.mednav.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

@Configuration
public class AgentScopeConfiguration {

    @Bean
    AgentScopeRuntimeInfo agentScopeRuntimeInfo(
            @Value("${mednav.agent.mode:stub}") String activeRuntime) {
        boolean corePresent = ClassUtils.isPresent(
                "io.agentscope.core.ReActAgent", getClass().getClassLoader());
        boolean openAiExtensionPresent = ClassUtils.isPresent(
                "io.agentscope.extensions.model.openai.OpenAIChatModel", getClass().getClassLoader());
        return new AgentScopeRuntimeInfo(corePresent, openAiExtensionPresent, activeRuntime);
    }

    public record AgentScopeRuntimeInfo(
            boolean corePresent, boolean openAiExtensionPresent, String activeRuntime) {}
}
