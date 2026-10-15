package com.example.mednav.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mednav.agui.AgentEvent;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationApiSmokeTest {

    private final WebTestClient client;

    @Autowired
    ConversationApiSmokeTest(@LocalServerPort int port) {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void runsFlywayMyBatisAndTheHttpEventFlow() {
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.myBatisProbe.status").isEqualTo("UP")
                .jsonPath("$.components.myBatisProbe.details.schemaVersion").isEqualTo("5")
                .jsonPath("$.components.cmsSnapshot.status").isEqualTo("UP");

        ConversationResponse conversation = client.post()
                .uri("/api/conversations")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ConversationResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(conversation).isNotNull();
        UUID conversationId = conversation.conversationId();

        client.post()
                .uri("/api/conversations/{id}/runs", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRunRequest("smoke_test_user"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("QUEUED");

        List<AgentEvent> events = client.get()
                .uri("/api/conversations/{id}/events", conversationId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AgentEvent.class)
                .getResponseBody()
                .take(6)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentEvent::type).containsExactly(
                "RUN_STARTED",
                "TEXT_MESSAGE_START",
                "TEXT_MESSAGE_CONTENT",
                "TEXT_MESSAGE_END",
                "AUTH_SECRET_REQUEST",
                "RUN_FINISHED");
    }
}
