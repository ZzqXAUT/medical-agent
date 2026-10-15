package com.example.mednav.agent;

import com.example.mednav.clinical.ClinicalSkillCatalog;
import com.example.mednav.config.AgentScopeModelConfiguration.ModelSettings;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Creates a fresh, stateless AgentScope agent for one clinical Run. */
@Component
public class MedicalNavigationAgentFactory {

    private final ObjectProvider<OpenAIChatModel> primaryModel;
    private final ModelSettings settings;
    private final ClinicalSkillCatalog skillCatalog;
    private final String systemPrompt;

    public MedicalNavigationAgentFactory(
            @Qualifier("primaryModel") ObjectProvider<OpenAIChatModel> primaryModel,
            ObjectProvider<ModelSettings> settingsProvider,
            ClinicalSkillCatalog skillCatalog,
            @Value("classpath:prompts/clinical-system-v1.txt") Resource promptResource) {
        this.primaryModel = primaryModel;
        this.settings = settingsProvider.getIfAvailable();
        this.skillCatalog = skillCatalog;
        try {
            this.systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load clinical system prompt", exception);
        }
    }

    public ReActAgent create() {
        if (settings == null) {
            throw new IllegalStateException(
                    "AgentScope OpenAI models are available only when mednav.agent.mode=openai");
        }
        GenerateOptions options = settings.generateOptions(
                settings.temperature(), settings.maxCompletionTokens());

        Toolkit toolkit = new Toolkit();
        SkillBox skillBox = new SkillBox(toolkit);
        for (var skill : skillCatalog.skills()) {
            skillBox.registerSkill(skill);
            skillBox.setSkillActive(skill.getSkillId(), true);
        }

        return ReActAgent.builder()
                .name("medical-navigation-agent")
                .description("Extracts symptoms and produces bounded disease hypotheses")
                .sysPrompt(systemPrompt)
                .model(primaryModel.getObject())
                .toolkit(toolkit)
                .skillBox(skillBox)
                .dynamicSkillsEnabled(false)
                .modelExecutionConfig(settings.executionConfig())
                .generateOptions(options)
                .maxIters(1)
                .build();
    }
}
