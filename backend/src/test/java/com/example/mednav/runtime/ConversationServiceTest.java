package com.example.mednav.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;
import static org.mockito.ArgumentMatchers.any;

import com.example.mednav.agui.AgentEvent;
import com.example.mednav.api.AuthSecretSubmission;
import com.example.mednav.api.ManualLocationRequest;
import com.example.mednav.auth.AuthPurpose;
import com.example.mednav.auth.AuthService;
import com.example.mednav.auth.AuthenticatedUser;
import com.example.mednav.auth.UsernameLookup;
import com.example.mednav.clinical.ClinicalPipelineService;
import com.example.mednav.conversation.ConversationHistoryService;
import com.example.mednav.location.GeoPoint;
import com.example.mednav.location.LocationPipelineService;
import com.example.mednav.location.LocationResult;
import com.example.mednav.location.LocationRoutingResult;
import com.example.mednav.location.LocationSource;
import com.example.mednav.location.LocationStatus;
import com.example.mednav.memory.MemoryService;
import com.example.mednav.memory.ExplicitMemoryFactExtractionService;
import com.example.mednav.memory.SessionMemoryFinalizer;
import com.example.mednav.memory.SessionFinalizationResult;
import com.example.mednav.memory.UserMemorySnapshot;
import com.example.mednav.profile.ProfileExtractionService;
import com.example.mednav.profile.ProfileDocument;
import com.example.mednav.profile.UserProfileService;
import com.example.mednav.recommendation.RecommendationPipelineService;
import com.example.mednav.route.RouteMatrixResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ConversationServiceTest {

    private final SessionRunScheduler scheduler = new SessionRunScheduler();
    private final AuthService authService = mock(AuthService.class);
    private final UserProfileService userProfileService = mock(UserProfileService.class);
    private final LocationPipelineService locationPipelineService = mock(LocationPipelineService.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final SessionMemoryFinalizer memoryFinalizer = mock(SessionMemoryFinalizer.class);
    private final ConversationService service = new ConversationService(
            scheduler,
            mock(ClinicalPipelineService.class),
            authService,
            userProfileService,
            new ProfileExtractionService(),
            new ExplicitMemoryFactExtractionService(),
            locationPipelineService,
            mock(RecommendationPipelineService.class),
            memoryService,
            memoryFinalizer,
            mock(ConversationHistoryService.class),
            Duration.ZERO);

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void emitsASecretRequestForANewUsername() {
        when(authService.lookupUsername("test_user"))
                .thenReturn(new UsernameLookup("test_user", "test_user", false));
        var conversation = service.createConversation();
        service.submitRun(conversation.conversationId(), "test_user");

        StepVerifier.create(service.events(conversation.conversationId()).take(6).collectList())
                .assertNext(events -> {
                    assertThat(events).extracting(event -> event.type()).containsExactly(
                            "RUN_STARTED",
                            "TEXT_MESSAGE_START",
                            "TEXT_MESSAGE_CONTENT",
                            "TEXT_MESSAGE_END",
                            "AUTH_SECRET_REQUEST",
                            "RUN_FINISHED");
                    assertThat(events.get(2).delta()).contains("test_user");
                    assertThat(events.get(4).data()).containsEntry("purpose", "REGISTER");
                })
                .verifyComplete();
    }

    @Test
    void aNewLocationRevisionSupersedesAnOlderAsynchronousResult() {
        when(authService.lookupUsername("test_user"))
                .thenReturn(new UsernameLookup("test_user", "test_user", false));
        when(authService.register("test_user", "password123"))
                .thenReturn(new AuthenticatedUser("user-1", "test_user", false));
        when(userProfileService.find("user-1"))
                .thenReturn(new ProfileDocument("user-1", Map.of(), Instant.now()));
        Sinks.One<LocationRoutingResult> first = Sinks.one();
        Sinks.One<LocationRoutingResult> second = Sinks.one();
        when(locationPipelineService.fromManualAddress("first")).thenReturn(first.asMono());
        when(locationPipelineService.fromManualAddress("second")).thenReturn(second.asMono());

        var conversation = service.createConversation();
        when(memoryService.initializeSession("user-1", conversation.sessionId().toString()))
                .thenReturn(new UserMemorySnapshot(
                        "user-1", false, Map.of(), List.of(), Map.of(), List.of()));
        var usernameRun = service.submitRun(conversation.conversationId(), "test_user");
        var secret = service.events(conversation.conversationId())
                .filter(event -> event.runId().equals(usernameRun.runId())
                        && "AUTH_SECRET_REQUEST".equals(event.type()))
                .next()
                .block(Duration.ofSeconds(2));
        assertThat(secret).isNotNull();
        service.submitSecret(
                        AuthPurpose.REGISTER,
                        new AuthSecretSubmission(
                                conversation.conversationId(),
                                UUID.fromString((String) secret.data().get("requestId")),
                                "password123"))
                .block(Duration.ofSeconds(2));

        var firstTask = service.submitManualLocation(
                new ManualLocationRequest(conversation.conversationId(), "first"));
        var secondTask = service.submitManualLocation(
                new ManualLocationRequest(conversation.conversationId(), "second"));
        first.tryEmitValue(locationResult("first"));
        second.tryEmitValue(locationResult("second"));

        assertThatThrownBy(() -> service.navigationRun(
                        conversation.conversationId(), firstTask.taskId()))
                .hasMessageContaining("current task");
        var current = service.navigationRun(conversation.conversationId(), secondTask.taskId());
        assertThat(current.locationRevision()).isEqualTo(2);
        assertThat(current.status()).isEqualTo(LocationStatus.READY);
        assertThat(current.location().formattedAddress()).isEqualTo("second");
    }

    @Test
    void idleLifecycleSchedulesFinalizationThroughTheSessionQueue() {
        when(authService.lookupUsername("idle_user"))
                .thenReturn(new UsernameLookup("idle_user", "idle_user", false));
        when(authService.register("idle_user", "password123"))
                .thenReturn(new AuthenticatedUser("idle-user-1", "idle_user", false));

        var conversation = service.createConversation();
        when(memoryService.initializeSession(
                        "idle-user-1", conversation.sessionId().toString()))
                .thenReturn(new UserMemorySnapshot(
                        "idle-user-1", false, Map.of(), List.of(), Map.of(), List.of()));
        when(memoryFinalizer.finalizeSession(
                        org.mockito.ArgumentMatchers.eq("idle-user-1"),
                        org.mockito.ArgumentMatchers.eq(conversation.sessionId().toString()),
                        any(Instant.class)))
                .thenReturn(Mono.just(new SessionFinalizationResult(
                        false, true, true, false, false)));
        when(memoryService.refreshUser("idle-user-1"))
                .thenReturn(new UserMemorySnapshot(
                        "idle-user-1", false, Map.of(), List.of(), Map.of(), List.of()));

        var usernameRun = service.submitRun(conversation.conversationId(), "idle_user");
        AgentEvent secret = service.events(conversation.conversationId())
                .filter(event -> event.runId().equals(usernameRun.runId())
                        && "AUTH_SECRET_REQUEST".equals(event.type()))
                .next()
                .block(Duration.ofSeconds(2));
        assertThat(secret).isNotNull();
        service.submitSecret(
                        AuthPurpose.REGISTER,
                        new AuthSecretSubmission(
                                conversation.conversationId(),
                                UUID.fromString((String) secret.data().get("requestId")),
                                "password123"))
                .block(Duration.ofSeconds(2));

        service.finalizeIdleSessions();

        verify(memoryFinalizer, timeout(2000)).finalizeSession(
                org.mockito.ArgumentMatchers.eq("idle-user-1"),
                org.mockito.ArgumentMatchers.eq(conversation.sessionId().toString()),
                any(Instant.class));
    }

    private LocationRoutingResult locationResult(String address) {
        return new LocationRoutingResult(
                new LocationResult(
                        new GeoPoint(31.2, -85.4),
                        address,
                        "Dothan",
                        "AL",
                        "36301",
                        "US",
                        "place",
                        LocationSource.MANUAL,
                        Instant.now()),
                new RouteMatrixResult(null, 0, 0, 0, 0, 0, List.of()));
    }
}
