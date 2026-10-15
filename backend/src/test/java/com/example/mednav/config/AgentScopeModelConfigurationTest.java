package com.example.mednav.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mednav.config.AgentScopeModelConfiguration.ModelSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentScopeModelConfigurationTest {

    @Test
    void propagatesReasoningEffortToEveryRequestOptions() {
        ModelSettings settings = new ModelSettings(
                "test-key",
                "",
                "gpt-5.6-terra",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "none",
                false,
                Duration.ofSeconds(45),
                2,
                0.2,
                1800);

        var options = settings.generateOptions(0.0, 500);

        assertThat(options.getReasoningEffort()).isEqualTo("none");
        assertThat(options.getMaxCompletionTokens()).isEqualTo(500);
        assertThat(options.getParallelToolCalls()).isNull();
    }
}
