package com.example.mednav.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mednav.agui.AgentEvent;
import com.example.mednav.cms.CmsDataset;
import com.example.mednav.cms.CmsImportFile;
import com.example.mednav.cms.CmsSnapshotImportService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocationConversationApiTest {

    private static final String PASSWORD = "CorrectHorse1!";

    private final WebTestClient client;

    @Autowired
    private CmsSnapshotImportService importService;

    @Autowired
    LocationConversationApiTest(@LocalServerPort int port) {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(8))
                .build();
    }

    @BeforeEach
    void publishCmsFixture() throws Exception {
        Path fixture = Path.of(getClass().getResource("/cms-fixture").toURI());
        importService.importSnapshot(List.of(
                CmsImportFile.local(
                        CmsDataset.HOSPITAL_GENERAL,
                        fixture.resolve(CmsDataset.HOSPITAL_GENERAL.defaultFileName())),
                CmsImportFile.local(
                        CmsDataset.DOCTOR_NATIONAL,
                        fixture.resolve(CmsDataset.DOCTOR_NATIONAL.defaultFileName())),
                CmsImportFile.local(
                        CmsDataset.FACILITY_AFFILIATION,
                        fixture.resolve(CmsDataset.FACILITY_AFFILIATION.defaultFileName()))));
    }

    @Test
    void supportsDeniedThenManualLocationAndJoinsWhenClinicalBecomesReady() {
        ConversationResponse conversation = register();

        LocationTaskAcceptedResponse denied = client.post()
                .uri("/api/location/consent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LocationConsentRequest(
                        conversation.conversationId(), false, null, null))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(LocationTaskAcceptedResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(denied).isNotNull();
        AgentEvent deniedEvent = awaitEvent(conversation, denied.taskId(), "LOCATION_STATUS");
        assertThat(deniedEvent.data())
                .containsEntry("status", "DENIED")
                .containsEntry("manualLocationRequired", true);

        LocationTaskAcceptedResponse manual = client.post()
                .uri("/api/location/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ManualLocationRequest(
                        conversation.conversationId(), "Dothan, AL 36301"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(LocationTaskAcceptedResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(manual).isNotNull();
        assertThat(manual.locationRevision()).isEqualTo(2);

        AgentEvent routes = awaitEvent(conversation, manual.taskId(), "ROUTE_MATRIX_RESULT");
        assertThat(routes.data())
                .containsEntry("hospitalCount", 1)
                .containsEntry("routeElementCount", 3)
                .containsEntry("batchCount", 3);

        submitRun(conversation, "1990-05-16, female, 165 cm, 55 kg");
        RunAcceptedResponse clinical = submitRun(
                conversation, "Severe chest pain for 2 hours, 8/10, with dizziness");
        AgentEvent join = awaitEvent(
                conversation, clinical.runId(), "NAVIGATION_CONTEXT_READY");
        assertThat(join.data())
                .containsEntry("locationRevision", 2)
                .containsEntry("clinicalRevision", 1)
                .containsEntry("hospitalCount", 1)
                .containsEntry("routeElementCount", 3);

        AgentEvent recommendation = awaitEvent(
                conversation, manual.taskId(), "RECOMMENDATION_RESULT");
        assertThat(recommendation.data()).containsKey("result");

        client.get()
                .uri("/api/navigation/runs/{taskId}", manual.taskId())
                .header("X-Conversation-Id", conversation.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("READY")
                .jsonPath("$.location.city").isEqualTo("Dothan")
                .jsonPath("$.routeMatrix.routes.length()").isEqualTo(1)
                .jsonPath("$.joinReady").isEqualTo(true);

        client.get()
                .uri("/api/navigation/runs/{taskId}/results", manual.taskId())
                .header("X-Conversation-Id", conversation.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("READY")
                .jsonPath("$.result.displayLanguage").isEqualTo("en-US")
                .jsonPath("$.result.diseases.length()").isEqualTo(3)
                .jsonPath("$.result.diseases[0].hypothesis.name")
                .isEqualTo("Acute coronary syndrome")
                .jsonPath("$.result.diseases[0].recommendationReason").isNotEmpty()
                .jsonPath("$.result.diseases[0].hospitals[0].hospitalId")
                .isEqualTo("010001")
                .jsonPath("$.result.diseases[0].hospitals[0].recommendationReason").isNotEmpty()
                .jsonPath("$.result.diseases[0].hospitals[0].doctors[0].npi")
                .isEqualTo("1003000126")
                .jsonPath("$.result.diseases[0].hospitals[0].doctors[0].recommendationReason")
                .isNotEmpty()
                .jsonPath("$.result.diseases[0].hospitals[0].doctors.length()")
                .isEqualTo(1)
                .jsonPath("$.result.semanticVerificationTriggered").isEqualTo(false);

        List<ConversationMessageResponse> history = client.get()
                .uri("/api/users/me/sessions/{sessionId}/messages", conversation.sessionId())
                .header("X-Conversation-Id", conversation.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ConversationMessageResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(history).isNotNull();
        List<ConversationMessageResponse> recommendationHistory = history.stream()
                .filter(message -> "RECOMMENDATION_RESULT".equals(message.messageType()))
                .toList();
        assertThat(recommendationHistory).hasSize(1);
        assertThat(recommendationHistory.get(0).structuredPayload()
                        .path("diseases").size())
                .isEqualTo(3);
        assertThat(recommendationHistory.get(0).structuredPayload()
                        .path("diseases").get(0).path("hospitals").size())
                .isEqualTo(1);
    }

    @Test
    void locationRequiresAnAuthenticatedConversation() {
        ConversationResponse conversation = createConversation();
        client.post()
                .uri("/api/location/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ManualLocationRequest(conversation.conversationId(), "36301"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    private ConversationResponse register() {
        ConversationResponse conversation = createConversation();
        RunAcceptedResponse username = submitRun(
                conversation, "location_" + UUID.randomUUID().toString().replace("-", ""));
        AgentEvent secret = awaitEvent(conversation, username.runId(), "AUTH_SECRET_REQUEST");
        client.post()
                .uri("/api/auth/conversation/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AuthSecretSubmission(
                        conversation.conversationId(),
                        UUID.fromString((String) secret.data().get("requestId")),
                        PASSWORD))
                .exchange()
                .expectStatus().isOk();
        return conversation;
    }

    private ConversationResponse createConversation() {
        ConversationResponse response = client.post()
                .uri("/api/conversations")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ConversationResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private RunAcceptedResponse submitRun(ConversationResponse conversation, String message) {
        RunAcceptedResponse response = client.post()
                .uri("/api/conversations/{id}/runs", conversation.conversationId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRunRequest(message))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(RunAcceptedResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private AgentEvent awaitEvent(
            ConversationResponse conversation, UUID runId, String eventType) {
        AgentEvent event = client.get()
                .uri("/api/conversations/{id}/events", conversation.conversationId())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AgentEvent.class)
                .getResponseBody()
                .filter(item -> item.runId().equals(runId) && eventType.equals(item.type()))
                .next()
                .block(Duration.ofSeconds(5));
        assertThat(event).isNotNull();
        return event;
    }
}
