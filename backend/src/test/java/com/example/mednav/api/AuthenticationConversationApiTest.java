package com.example.mednav.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mednav.agui.AgentEvent;
import com.example.mednav.auth.UserAccount;
import com.example.mednav.auth.UserAccountMapper;
import com.example.mednav.profile.UserProfileMapper;
import com.example.mednav.profile.UserProfileRow;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
class AuthenticationConversationApiTest {

    private static final String PASSWORD = "CorrectHorse1!";

    private final WebTestClient client;

    @Autowired
    private UserAccountMapper accountMapper;

    @Autowired
    private UserProfileMapper profileMapper;

    @Autowired
    AuthenticationConversationApiTest(@LocalServerPort int port) {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(8))
                .build();
    }

    @Test
    void registersCollectsProfileAndPersistsOnlyWhenSessionCompletes() {
        String username = uniqueUsername();
        ConversationResponse conversation = createConversation();
        AgentEvent secretRequest = requestSecret(conversation, username, "REGISTER");

        AuthConversationResponse auth = submitSecret(
                "/api/auth/conversation/register", conversation, secretRequest, PASSWORD, 200);
        assertThat(auth.newUser()).isTrue();

        UserAccount account = accountMapper.findByCanonicalUsername(username.toLowerCase(Locale.ROOT));
        assertThat(account).isNotNull();
        assertThat(account.passwordHash()).startsWith("$2").doesNotContain(PASSWORD);

        client.get()
                .uri("/api/users/me/profile")
                .header("X-Conversation-Id", conversation.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.profile").isEmpty()
                .jsonPath("$.hasUncommittedSessionUpdates").isEqualTo(false);

        RunAcceptedResponse profileRun = submitRun(
                conversation, "1990-05-16, female, 165 cm, 55 kg");
        AgentEvent profileEvent = awaitEvent(conversation, profileRun.runId(), "PROFILE_UPDATED");
        assertThat(profileEvent.data()).containsEntry("complete", true);

        RunAcceptedResponse clinicalRun = submitRun(
                conversation,
                "I was diagnosed with asthma. Drug allergy: penicillin. "
                        + "Food allergy: peanuts. I cannot take ibuprofen. I am vegetarian. "
                        + "Severe headache for 2 hours, 8/10, with vomiting");
        AgentEvent clinicalResult = awaitEvent(
                conversation, clinicalRun.runId(), "CLINICAL_RESULT");
        assertThat(clinicalResult.data()).containsEntry("decision", "READY");
        assertThat((List<?>) clinicalResult.data().get("hypotheses")).hasSize(3);
        assertThat(clinicalResult.data().toString()).doesNotContain("targetSpecialties");

        client.get()
                .uri("/api/users/me/profile")
                .header("X-Conversation-Id", conversation.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.profile.birth_date").isEqualTo("1990-05-16")
                .jsonPath("$.profile.sex").isEqualTo("female")
                .jsonPath("$.hasUncommittedSessionUpdates").isEqualTo(true);

        client.post()
                .uri("/api/conversations/{id}/complete", conversation.conversationId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.profilePersisted").isEqualTo(true);

        UserProfileRow storedMemory = profileMapper.findByUserId(auth.userId());
        assertThat(storedMemory.diseaseHistoryJson()).contains("asthma");
        assertThat(storedMemory.coreConstraintsJson())
                .contains("drug_allergies", "penicillin")
                .contains("food_allergies", "peanuts")
                .contains("medication_contraindications", "ibuprofen")
                .contains("dietary_restrictions", "vegetarian");

        client.get()
                .uri("/api/users/me/profile")
                .header("X-Conversation-Id", conversation.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.profile.height_cm").isEqualTo(165.0)
                .jsonPath("$.profile.weight_kg").isEqualTo(55.0)
                .jsonPath("$.hasUncommittedSessionUpdates").isEqualTo(false);

        // Repeating finalization is idempotent and must not create duplicate history rows.
        client.post()
                .uri("/api/conversations/{id}/complete", conversation.conversationId())
                .exchange()
                .expectStatus().isOk();

        client.get()
                .uri("/api/users/me/session-memories")
                .header("X-Conversation-Id", conversation.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].sessionId").isEqualTo(conversation.sessionId().toString())
                .jsonPath("$[0].preliminaryDiseases.length()").isEqualTo(3)
                .jsonPath("$[0].summary").isNotEmpty();

        client.delete()
                .uri("/api/users/me/session-memories/{sessionId}", conversation.sessionId())
                .header("X-Conversation-Id", conversation.conversationId().toString())
                .exchange()
                .expectStatus().isNoContent();

        client.get()
                .uri("/api/users/me/session-memories")
                .header("X-Conversation-Id", conversation.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);

        List<AgentEvent> allEvents = client.get()
                .uri("/api/conversations/{id}/events", conversation.conversationId())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .returnResult(AgentEvent.class)
                .getResponseBody()
                .takeUntil(event -> "SESSION_COMPLETED".equals(event.type()))
                .collectList()
                .block(Duration.ofSeconds(5));
        assertThat(allEvents).isNotNull();
        assertThat(allEvents.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void rejectsWrongPasswordAndAllowsRetryOnTheSameSecretRequest() {
        String username = uniqueUsername();
        ConversationResponse registration = createConversation();
        AgentEvent registerSecret = requestSecret(registration, username, "REGISTER");
        submitSecret("/api/auth/conversation/register", registration, registerSecret, PASSWORD, 200);

        ConversationResponse login = createConversation();
        AgentEvent loginSecret = requestSecret(login, username.toUpperCase(Locale.ROOT), "LOGIN");
        submitSecret("/api/auth/conversation/login", login, loginSecret, "WrongPassword!", 401);

        AuthConversationResponse authenticated = submitSecret(
                "/api/auth/conversation/login", login, loginSecret, PASSWORD, 200);
        assertThat(authenticated.newUser()).isFalse();
        assertThat(authenticated.username()).isEqualTo(username);
    }

    @Test
    void databaseUniqueConstraintResolvesConcurrentRegistrationRace() {
        String username = uniqueUsername();
        ConversationResponse first = createConversation();
        ConversationResponse second = createConversation();
        AgentEvent firstRequest = requestSecret(first, username, "REGISTER");
        AgentEvent secondRequest = requestSecret(second, username.toUpperCase(Locale.ROOT), "REGISTER");

        submitSecret("/api/auth/conversation/register", first, firstRequest, PASSWORD, 200);
        submitSecret("/api/auth/conversation/register", second, secondRequest, PASSWORD, 409);
    }

    @Test
    void profileCollectionStopsAfterThreeIncompleteRounds() {
        ConversationResponse conversation = createConversation();
        AgentEvent request = requestSecret(conversation, uniqueUsername(), "REGISTER");
        submitSecret("/api/auth/conversation/register", conversation, request, PASSWORD, 200);

        AgentEvent event = null;
        for (int round = 1; round <= 3; round++) {
            RunAcceptedResponse run = submitRun(conversation, "not provided in round " + round);
            event = awaitEvent(conversation, run.runId(), "PROFILE_UPDATED");
        }

        assertThat(event).isNotNull();
        assertThat(event.data())
                .containsEntry("round", 3)
                .containsEntry("complete", true);
        assertThat((List<?>) event.data().get("missingFields")).hasSize(4);
    }

    @Test
    void listsResumesAndReadsMultipleSessionsWhileCompletedSessionsStayReadOnly() {
        String username = uniqueUsername();
        ConversationResponse first = createConversation();
        AgentEvent request = requestSecret(first, username, "REGISTER");
        submitSecret("/api/auth/conversation/register", first, request, PASSWORD, 200);

        client.get()
                .uri("/api/users/me/sessions")
                .header("X-Conversation-Id", first.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].sessionId").isEqualTo(first.sessionId().toString())
                .jsonPath("$[0].resumable").isEqualTo(true);

        client.get()
                .uri("/api/users/me/sessions/{sessionId}/messages", first.sessionId())
                .header("X-Conversation-Id", first.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].role").isEqualTo("USER")
                .jsonPath("$[0].content").isEqualTo(username);

        ConversationResponse second = client.post()
                .uri("/api/users/me/sessions")
                .header("X-Conversation-Id", first.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody(ConversationResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(second).isNotNull();

        client.post()
                .uri("/api/users/me/sessions/{sessionId}/resume", second.sessionId())
                .header("X-Conversation-Id", first.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.conversationId").isEqualTo(second.conversationId().toString());

        client.post()
                .uri("/api/conversations/{id}/complete", second.conversationId())
                .exchange()
                .expectStatus().isOk();

        client.post()
                .uri("/api/users/me/sessions/{sessionId}/resume", second.sessionId())
                .header("X-Conversation-Id", first.conversationId().toString())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SESSION_NOT_RESUMABLE");

        client.get()
                .uri("/api/users/me/sessions/{sessionId}/messages", second.sessionId())
                .header("X-Conversation-Id", first.conversationId().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].role").isEqualTo("ASSISTANT")
                .jsonPath("$[1].content").value(value ->
                        assertThat(value.toString()).contains("Session 已结束"));

        ConversationResponse otherUser = createConversation();
        AgentEvent otherRequest = requestSecret(otherUser, uniqueUsername(), "REGISTER");
        submitSecret(
                "/api/auth/conversation/register",
                otherUser,
                otherRequest,
                PASSWORD,
                200);
        client.get()
                .uri("/api/users/me/sessions/{sessionId}/messages", second.sessionId())
                .header("X-Conversation-Id", otherUser.conversationId().toString())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SESSION_NOT_FOUND");
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

    private AgentEvent requestSecret(
            ConversationResponse conversation, String username, String expectedPurpose) {
        RunAcceptedResponse run = submitRun(conversation, username);
        AgentEvent event = awaitEvent(conversation, run.runId(), "AUTH_SECRET_REQUEST");
        assertThat(event.data()).containsEntry("purpose", expectedPurpose);
        return event;
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

    private AuthConversationResponse submitSecret(
            String path,
            ConversationResponse conversation,
            AgentEvent secretRequest,
            String password,
            int expectedStatus) {
        AuthSecretSubmission body = new AuthSecretSubmission(
                conversation.conversationId(),
                UUID.fromString((String) secretRequest.data().get("requestId")),
                password);
        WebTestClient.ResponseSpec exchange = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus);
        if (expectedStatus != 200) {
            exchange.expectBody(ApiError.class);
            return null;
        }
        AuthConversationResponse response = exchange
                .expectBody(AuthConversationResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private String uniqueUsername() {
        return "user_" + UUID.randomUUID().toString().replace("-", "");
    }
}
