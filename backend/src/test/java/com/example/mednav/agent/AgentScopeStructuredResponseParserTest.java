package com.example.mednav.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mednav.memory.SessionSummaryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

class AgentScopeStructuredResponseParserTest {

    private final AgentScopeStructuredResponseParser parser =
            new AgentScopeStructuredResponseParser(new ObjectMapper());

    @Test
    void parsesJsonInsideMarkdownTextWhenStructuredDataIsAbsent() {
        Msg message = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("```json\n{\"summary\":\"Severe headache for two hours.\"}\n```")
                .build();

        SessionSummaryResult result = parser.parse(message, SessionSummaryResult.class);

        assertThat(result.summary()).isEqualTo("Severe headache for two hours.");
    }
}
