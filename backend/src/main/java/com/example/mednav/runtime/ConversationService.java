package com.example.mednav.runtime;

import static com.example.mednav.agui.AgentEventType.AUTH_RESULT;
import static com.example.mednav.agui.AgentEventType.AUTH_SECRET_REQUEST;
import static com.example.mednav.agui.AgentEventType.CLINICAL_QUESTION;
import static com.example.mednav.agui.AgentEventType.CLINICAL_RESULT;
import static com.example.mednav.agui.AgentEventType.LOCATION_STATUS;
import static com.example.mednav.agui.AgentEventType.LOCAL_REPAIR_PROGRESS;
import static com.example.mednav.agui.AgentEventType.NAVIGATION_CONTEXT_READY;
import static com.example.mednav.agui.AgentEventType.PIPELINE_PROGRESS;
import static com.example.mednav.agui.AgentEventType.PROFILE_UPDATED;
import static com.example.mednav.agui.AgentEventType.ROUTE_MATRIX_RESULT;
import static com.example.mednav.agui.AgentEventType.RECOMMENDATION_RESULT;
import static com.example.mednav.agui.AgentEventType.RUN_FINISHED;
import static com.example.mednav.agui.AgentEventType.RUN_STARTED;
import static com.example.mednav.agui.AgentEventType.SESSION_COMPLETED;
import static com.example.mednav.agui.AgentEventType.TEXT_MESSAGE_CONTENT;
import static com.example.mednav.agui.AgentEventType.TEXT_MESSAGE_END;
import static com.example.mednav.agui.AgentEventType.TEXT_MESSAGE_START;
import static com.example.mednav.agui.AgentEventType.VALIDATION_FINDING;

import com.example.mednav.agent.AgentRunResult;
import com.example.mednav.agui.AgentEvent;
import com.example.mednav.agui.AgentEventType;
import com.example.mednav.api.ApiException;
import com.example.mednav.api.AuthConversationResponse;
import com.example.mednav.api.AuthSecretSubmission;
import com.example.mednav.api.ConversationCompletionResponse;
import com.example.mednav.api.ConversationMessageResponse;
import com.example.mednav.api.ConversationResponse;
import com.example.mednav.api.LocationConsentRequest;
import com.example.mednav.api.LocationTaskAcceptedResponse;
import com.example.mednav.api.ManualLocationRequest;
import com.example.mednav.api.NavigationRunResponse;
import com.example.mednav.api.RecommendationRunResponse;
import com.example.mednav.api.RunAcceptedResponse;
import com.example.mednav.api.SessionMemoryResponse;
import com.example.mednav.api.UserProfileResponse;
import com.example.mednav.api.UserSessionResponse;
import com.example.mednav.auth.AuthPurpose;
import com.example.mednav.auth.AuthService;
import com.example.mednav.auth.AuthenticatedUser;
import com.example.mednav.auth.UsernameLookup;
import com.example.mednav.clinical.ClinicalAssessmentRequest;
import com.example.mednav.clinical.ClinicalDecision;
import com.example.mednav.clinical.ClinicalModelUnavailableException;
import com.example.mednav.clinical.ClinicalPipelineService;
import com.example.mednav.clinical.ClinicalReadiness;
import com.example.mednav.clinical.ClinicalValidationException;
import com.example.mednav.clinical.DiseaseHypothesis;
import com.example.mednav.clinical.SymptomCase;
import com.example.mednav.conversation.ConversationHistoryService;
import com.example.mednav.conversation.ConversationMessageRow;
import com.example.mednav.conversation.ConversationSessionRow;
import com.example.mednav.location.GeoPoint;
import com.example.mednav.location.LocationException;
import com.example.mednav.location.LocationPipelineService;
import com.example.mednav.location.LocationResult;
import com.example.mednav.location.LocationRoutingResult;
import com.example.mednav.location.LocationStatus;
import com.example.mednav.i18n.DisplayLanguage;
import com.example.mednav.memory.EffectiveMemory;
import com.example.mednav.memory.ExplicitMemoryFactExtractionService;
import com.example.mednav.memory.ExplicitMemoryFacts;
import com.example.mednav.memory.SessionFinalizationResult;
import com.example.mednav.memory.SessionMemoryFinalizer;
import com.example.mednav.memory.UserMemorySnapshot;
import com.example.mednav.memory.MemoryService;
import com.example.mednav.profile.ProfileDocument;
import com.example.mednav.profile.ProfileExtractionResult;
import com.example.mednav.profile.ProfileExtractionService;
import com.example.mednav.profile.UserProfileService;
import com.example.mednav.recommendation.NavigationRecommendationResult;
import com.example.mednav.recommendation.RecommendationPipelineService;
import com.example.mednav.recommendation.RecommendationRequest;
import com.example.mednav.recommendation.RecommendationStatus;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.Disposable;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final Map<UUID, ConversationState> conversations = new ConcurrentHashMap<>();
    private final Map<UUID, ConversationState> sessions = new ConcurrentHashMap<>();
    private final SessionRunScheduler scheduler;
    private final ClinicalPipelineService clinicalPipelineService;
    private final AuthService authService;
    private final UserProfileService userProfileService;
    private final ProfileExtractionService profileExtractionService;
    private final ExplicitMemoryFactExtractionService explicitMemoryFactExtractionService;
    private final LocationPipelineService locationPipelineService;
    private final RecommendationPipelineService recommendationPipelineService;
    private final MemoryService memoryService;
    private final SessionMemoryFinalizer memoryFinalizer;
    private final ConversationHistoryService conversationHistoryService;
    private final Duration idleTimeout;

    public ConversationService(
            SessionRunScheduler scheduler,
            ClinicalPipelineService clinicalPipelineService,
            AuthService authService,
            UserProfileService userProfileService,
            ProfileExtractionService profileExtractionService,
            ExplicitMemoryFactExtractionService explicitMemoryFactExtractionService,
            LocationPipelineService locationPipelineService,
            RecommendationPipelineService recommendationPipelineService,
            MemoryService memoryService,
            SessionMemoryFinalizer memoryFinalizer,
            ConversationHistoryService conversationHistoryService,
            @Value("${mednav.memory.idle-timeout:10m}") Duration idleTimeout) {
        this.scheduler = scheduler;
        this.clinicalPipelineService = clinicalPipelineService;
        this.authService = authService;
        this.userProfileService = userProfileService;
        this.profileExtractionService = profileExtractionService;
        this.explicitMemoryFactExtractionService = explicitMemoryFactExtractionService;
        this.locationPipelineService = locationPipelineService;
        this.recommendationPipelineService = recommendationPipelineService;
        this.memoryService = memoryService;
        this.memoryFinalizer = memoryFinalizer;
        this.conversationHistoryService = conversationHistoryService;
        this.idleTimeout = idleTimeout;
    }

    public ConversationResponse createConversation() {
        Instant createdAt = Instant.now();
        ConversationState state = new ConversationState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                createdAt,
                Sinks.many().replay().limit(150));
        conversations.put(state.conversationId, state);
        sessions.put(state.sessionId, state);
        return new ConversationResponse(state.conversationId, state.sessionId, createdAt);
    }

    public ConversationResponse createAuthenticatedSession(UUID authenticationConversationId) {
        ConversationState authenticated = requireAuthenticatedConversation(authenticationConversationId);
        Instant createdAt = Instant.now();
        ConversationState state = new ConversationState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                createdAt,
                Sinks.many().replay().limit(150));
        state.userId = authenticated.userId;
        state.username = authenticated.username;
        state.phase = ConversationPhase.READY;
        UserMemorySnapshot memory =
                memoryService.initializeSession(state.userId, state.sessionId.toString());
        state.persistedProfile.putAll(memory.profile());
        conversationHistoryService.bindSession(
                state.userId,
                state.sessionId.toString(),
                state.conversationId.toString(),
                createdAt,
                List.of());
        conversations.put(state.conversationId, state);
        sessions.put(state.sessionId, state);
        publishTextRun(
                state,
                UUID.randomUUID(),
                "已创建新的 Session。请描述你这次需要咨询的症状。",
                List.of());
        return new ConversationResponse(state.conversationId, state.sessionId, createdAt);
    }

    public RunAcceptedResponse submitRun(UUID conversationId, String message) {
        ConversationState state = requireConversation(conversationId);
        touch(state);
        UUID runId = UUID.randomUUID();
        String normalizedMessage = message.strip();
        persistMessage(state, UUID.randomUUID().toString(), runId, "USER", normalizedMessage);
        if (state.userId != null && state.phase == ConversationPhase.READY) {
            conversationHistoryService.setInitialTitle(
                    state.sessionId.toString(), normalizedMessage);
        }

        scheduler.schedule(
                        state.sessionId.toString(),
                        () -> executeRun(state, runId, normalizedMessage))
                .doOnError(error -> publishRunError(state, runId, error))
                .subscribe(
                        ignored -> log.info(
                                "run_completed conversationId={} runId={}", conversationId, runId),
                        error -> log.warn(
                                "run_failed conversationId={} runId={} errorCode={}",
                                conversationId,
                                runId,
                                error instanceof ApiException apiException
                                        ? apiException.code()
                                        : "UNEXPECTED"));

        return new RunAcceptedResponse(
                runId, conversationId, state.sessionId, "QUEUED", Instant.now());
    }

    public Mono<AuthConversationResponse> submitSecret(
            AuthPurpose purpose, AuthSecretSubmission request) {
        ConversationState state = requireConversation(request.conversationId());
        touch(state);
        UUID runId = UUID.randomUUID();
        return scheduler.schedule(
                state.sessionId.toString(),
                () -> Mono.fromCallable(() -> authenticate(state, runId, purpose, request)));
    }

    public Mono<ConversationCompletionResponse> completeConversation(UUID conversationId) {
        ConversationState state = requireConversation(conversationId);
        touch(state);
        return scheduler.schedule(
                state.sessionId.toString(),
                () -> completeSession(state, false));
    }

    public Mono<UserProfileResponse> profile(UUID conversationId) {
        ConversationState state = requireAuthenticatedConversation(conversationId);
        return scheduler.schedule(state.sessionId.toString(), () -> Mono.fromCallable(() -> {
                    ProfileDocument persisted = userProfileService.find(state.userId);
                    if (state.phase == ConversationPhase.COMPLETED) {
                        return new UserProfileResponse(
                                state.userId,
                                persisted.profile(),
                                persisted.updatedAt(),
                                false);
                    }
                    EffectiveMemory memory = memoryService.effective(
                            state.userId, state.sessionId.toString());
                    return new UserProfileResponse(
                            state.userId,
                            memory.profile(),
                            persisted.updatedAt(),
                            !memory.currentSession().profile().isEmpty());
                }));
    }

    public Mono<List<SessionMemoryResponse>> sessionMemories(UUID conversationId) {
        ConversationState state = requireAuthenticatedConversation(conversationId);
        return scheduler.schedule(
                state.sessionId.toString(),
                () -> Mono.fromCallable(() -> memoryService.history(state.userId).stream()
                        .map(SessionMemoryResponse::from)
                        .toList()));
    }

    public List<UserSessionResponse> userSessions(UUID conversationId) {
        ConversationState authenticated = requireAuthenticatedConversation(conversationId);
        Instant cutoff = Instant.now().minus(idleTimeout);
        return conversationHistoryService.sessions(authenticated.userId).stream()
                .map(row -> new UserSessionResponse(
                        UUID.fromString(row.sessionId()),
                        UUID.fromString(row.conversationId()),
                        row.title(),
                        row.status(),
                        isResumable(row, authenticated.userId, cutoff),
                        row.createdAt(),
                        row.lastActivityAt(),
                        row.completedAt()))
                .toList();
    }

    public List<ConversationMessageResponse> sessionMessages(
            UUID conversationId, UUID sessionId) {
        ConversationState authenticated = requireAuthenticatedConversation(conversationId);
        requireOwnedSession(authenticated.userId, sessionId);
        return conversationHistoryService.messages(sessionId.toString()).stream()
                .map(message -> new ConversationMessageResponse(
                        UUID.fromString(message.id()),
                        message.role(),
                        message.content(),
                        message.messageType(),
                        conversationHistoryService.parsePayload(message.structuredPayload()),
                        message.createdAt()))
                .toList();
    }

    public ConversationResponse resumeSession(UUID conversationId, UUID sessionId) {
        ConversationState authenticated = requireAuthenticatedConversation(conversationId);
        ConversationSessionRow stored = requireOwnedSession(authenticated.userId, sessionId);
        ConversationState target = sessions.get(sessionId);
        Instant cutoff = Instant.now().minus(idleTimeout);
        if (!"ACTIVE".equals(stored.status())
                || target == null
                || target.phase == ConversationPhase.COMPLETED
                || target.finalizationRequested
                || !target.lastActivityAt.isAfter(cutoff)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "SESSION_NOT_RESUMABLE",
                    "This Session is closed or its 10-minute resume window has expired.");
        }
        if (!authenticated.userId.equals(target.userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found.");
        }
        touch(target);
        return new ConversationResponse(
                target.conversationId, target.sessionId, target.createdAt);
    }

    public Mono<Void> deleteSessionMemory(UUID conversationId, UUID sessionId) {
        ConversationState state = requireAuthenticatedConversation(conversationId);
        return scheduler.schedule(state.sessionId.toString(), () -> Mono.fromRunnable(() -> {
                    if (state.sessionId.equals(sessionId)
                            && state.phase != ConversationPhase.COMPLETED) {
                        throw new ApiException(
                                HttpStatus.CONFLICT,
                                "SESSION_MEMORY_ACTIVE",
                                "The active Session cannot be deleted before it is finalized.");
                    }
                    if (!memoryService.deleteHistory(state.userId, sessionId.toString())) {
                        throw new ApiException(
                                HttpStatus.NOT_FOUND,
                                "SESSION_MEMORY_NOT_FOUND",
                                "The Session memory does not exist for this user.");
                    }
                }))
                .then();
    }

    public LocationTaskAcceptedResponse submitLocationConsent(LocationConsentRequest request) {
        ConversationState state = requireAuthenticatedConversation(request.conversationId());
        touch(state);
        if (!request.granted()) {
            return denyLocation(state);
        }
        if (request.latitude() == null || request.longitude() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "LOCATION_COORDINATES_REQUIRED",
                    "Granted browser location requires latitude and longitude.");
        }
        GeoPoint point;
        try {
            point = new GeoPoint(request.latitude(), request.longitude());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "LOCATION_COORDINATES_INVALID", exception.getMessage());
        }
        return startLocationTask(state, locationPipelineService.fromBrowser(point));
    }

    public LocationTaskAcceptedResponse submitManualLocation(ManualLocationRequest request) {
        ConversationState state = requireAuthenticatedConversation(request.conversationId());
        touch(state);
        return startLocationTask(
                state, locationPipelineService.fromManualAddress(request.address().strip()));
    }

    public NavigationRunResponse navigationRun(UUID conversationId, UUID taskId) {
        ConversationState state = requireAuthenticatedConversation(conversationId);
        synchronized (state) {
            if (!taskId.equals(state.locationTaskId)) {
                throw new ApiException(
                        HttpStatus.NOT_FOUND,
                        "LOCATION_TASK_NOT_FOUND",
                        "Location task is not the current task for this conversation.");
            }
            return new NavigationRunResponse(
                    taskId,
                    state.conversationId,
                    state.contextVersion,
                    state.locationRevision,
                    state.clinicalRevision,
                    state.locationStatus,
                    state.locationRoutingResult == null
                            ? null
                            : state.locationRoutingResult.location(),
                    state.locationRoutingResult == null
                            ? null
                            : state.locationRoutingResult.routeMatrix(),
                    state.locationErrorCode,
                    isJoinReady(state));
        }
    }

    public RecommendationRunResponse recommendationRun(UUID conversationId, UUID taskId) {
        ConversationState state = requireAuthenticatedConversation(conversationId);
        synchronized (state) {
            if (!taskId.equals(state.locationTaskId)) {
                throw new ApiException(
                        HttpStatus.NOT_FOUND,
                        "LOCATION_TASK_NOT_FOUND",
                        "Location task is not the current task for this conversation.");
            }
            return new RecommendationRunResponse(
                    taskId,
                    state.conversationId,
                    state.contextVersion,
                    state.locationRevision,
                    state.clinicalRevision,
                    state.recommendationStatus,
                    state.recommendationResult,
                    state.recommendationErrorCode);
        }
    }

    public Flux<AgentEvent> events(UUID conversationId) {
        return requireConversation(conversationId).events.asFlux();
    }

    private LocationTaskAcceptedResponse denyLocation(ConversationState state) {
        UUID taskId = UUID.randomUUID();
        long revision;
        synchronized (state) {
            cancelLocationTask(state);
            invalidateRecommendation(state);
            revision = ++state.locationRevision;
            state.contextVersion++;
            state.locationTaskId = taskId;
            state.locationStatus = LocationStatus.DENIED;
            state.locationRoutingResult = null;
            state.locationErrorCode = "LOCATION_PERMISSION_DENIED";
            state.lastJoinedRevisions = null;
            state.locationEventSequence = new AtomicLong();
            publishLocationStatus(state, taskId, "DENIED", true);
        }
        log.info(
                "location_permission_denied conversationId={} taskId={} locationRevision={}",
                state.conversationId,
                taskId,
                revision);
        return new LocationTaskAcceptedResponse(
                taskId, state.conversationId, revision, LocationStatus.DENIED, Instant.now());
    }

    private LocationTaskAcceptedResponse startLocationTask(
            ConversationState state, Mono<LocationRoutingResult> pipeline) {
        UUID taskId = UUID.randomUUID();
        long revision;
        synchronized (state) {
            if (state.phase == ConversationPhase.COMPLETED) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "SESSION_COMPLETED",
                        "Location cannot be changed after the Session has ended.");
            }
            cancelLocationTask(state);
            invalidateRecommendation(state);
            revision = ++state.locationRevision;
            state.contextVersion++;
            state.locationTaskId = taskId;
            state.locationStatus = LocationStatus.RUNNING;
            state.locationRoutingResult = null;
            state.locationErrorCode = null;
            state.lastJoinedRevisions = null;
            state.locationEventSequence = new AtomicLong();
            publishLocationStatus(state, taskId, "RUNNING", false);
        }
        Disposable subscription = pipeline.subscribe(
                result -> commitLocationResult(state, taskId, revision, result),
                error -> failLocationTask(state, taskId, revision, error));
        synchronized (state) {
            if (taskId.equals(state.locationTaskId)
                    && state.locationStatus == LocationStatus.RUNNING) {
                state.locationSubscription = subscription;
            }
        }
        log.info(
                "location_task_started conversationId={} taskId={} locationRevision={}",
                state.conversationId,
                taskId,
                revision);
        return new LocationTaskAcceptedResponse(
                taskId, state.conversationId, revision, LocationStatus.RUNNING, Instant.now());
    }

    private void commitLocationResult(
            ConversationState state,
            UUID taskId,
            long revision,
            LocationRoutingResult result) {
        synchronized (state) {
            if (!taskId.equals(state.locationTaskId)
                    || revision != state.locationRevision
                    || state.phase == ConversationPhase.COMPLETED) {
                log.info(
                        "location_result_discarded conversationId={} taskId={} reason=STALE_REVISION",
                        state.conversationId,
                        taskId);
                return;
            }
            state.locationStatus = LocationStatus.READY;
            state.locationRoutingResult = result;
            state.locationErrorCode = null;
            state.locationSubscription = null;
            state.contextVersion++;
            Map<String, Object> joinData = claimJoinData(state);
            publishLocationStatus(state, taskId, "READY", false);
            Map<String, Object> routeData = new LinkedHashMap<>();
            routeData.put("locationRevision", revision);
            routeData.put("clinicalRevision", state.clinicalRevision);
            routeData.put("hospitalCount", result.routeMatrix().hospitalCount());
            routeData.put("routeElementCount", result.routeMatrix().routeElementCount());
            routeData.put("batchCount", result.routeMatrix().batchCount());
            routeData.put("cacheHits", result.routeMatrix().cacheHits());
            routeData.put("cacheMisses", result.routeMatrix().cacheMisses());
            if (result.routeMatrix().snapshotId() != null) {
                routeData.put("cmsSnapshotId", result.routeMatrix().snapshotId());
            }
            publishLocationEvent(state, taskId, ROUTE_MATRIX_RESULT, Map.copyOf(routeData));
            if (joinData != null) {
                publishLocationEvent(state, taskId, NAVIGATION_CONTEXT_READY, joinData);
            }
        }
        log.info(
                "location_task_completed conversationId={} taskId={} locationRevision={} hospitals={} routeElements={} batches={}",
                state.conversationId,
                taskId,
                revision,
                result.routeMatrix().hospitalCount(),
                result.routeMatrix().routeElementCount(),
                result.routeMatrix().batchCount());
        startRecommendationIfReady(state);
    }

    private void failLocationTask(
            ConversationState state, UUID taskId, long revision, Throwable error) {
        String code = error instanceof LocationException locationError
                ? locationError.code()
                : "LOCATION_PIPELINE_FAILED";
        LocationStatus status = error instanceof LocationException locationError
                        && locationError.invalidInput()
                ? LocationStatus.INVALID
                : LocationStatus.FAILED;
        synchronized (state) {
            if (!taskId.equals(state.locationTaskId)
                    || revision != state.locationRevision
                    || state.phase == ConversationPhase.COMPLETED) {
                return;
            }
            state.locationStatus = status;
            state.locationRoutingResult = null;
            state.locationErrorCode = code;
            state.locationSubscription = null;
            state.contextVersion++;
            publishLocationStatus(state, taskId, status.name(), true);
            publishLocationEvent(
                    state,
                    taskId,
                    VALIDATION_FINDING,
                    Map.of("stage", "LOCATION", "code", code, "recoverable", true));
        }
        log.warn(
                "location_task_failed conversationId={} taskId={} locationRevision={} status={} code={} errorType={}",
                state.conversationId,
                taskId,
                revision,
                status,
                code,
                error.getClass().getSimpleName());
    }

    private void cancelLocationTask(ConversationState state) {
        if (state.locationSubscription != null) {
            state.locationSubscription.dispose();
            state.locationSubscription = null;
        }
    }

    private void invalidateRecommendation(ConversationState state) {
        if (state.recommendationSubscription != null) {
            state.recommendationSubscription.dispose();
            state.recommendationSubscription = null;
        }
        state.recommendationStatus = RecommendationStatus.WAITING;
        state.recommendationResult = null;
        state.recommendationErrorCode = null;
        state.recommendationRevisions = null;
    }

    private void startRecommendationIfReady(ConversationState state) {
        RecommendationRequest request;
        synchronized (state) {
            if (!isJoinReady(state) || state.locationTaskId == null) {
                return;
            }
            String revisions = state.locationRevision + ":" + state.clinicalRevision;
            if (revisions.equals(state.recommendationRevisions)) {
                return;
            }
            invalidateRecommendation(state);
            state.recommendationRevisions = revisions;
            state.recommendationStatus = RecommendationStatus.RUNNING;
            request = new RecommendationRequest(
                    state.conversationId,
                    state.locationTaskId,
                    state.contextVersion,
                    state.locationRevision,
                    state.clinicalRevision,
                    state.latestClinicalDecision,
                    state.locationRoutingResult,
                    state.displayLanguage);
            publishRecommendationEvent(
                    state,
                    state.locationTaskId,
                    PIPELINE_PROGRESS,
                    Map.of("stage", "RECOMMENDATION", "status", "RUNNING"));
        }
        Disposable subscription = recommendationPipelineService.generateAndVerify(request)
                .subscribe(
                        result -> commitRecommendation(state, request, result),
                        error -> failRecommendation(state, request, error));
        synchronized (state) {
            if (matchesRecommendation(state, request)
                    && state.recommendationStatus == RecommendationStatus.RUNNING) {
                state.recommendationSubscription = subscription;
            }
        }
    }

    private void commitRecommendation(
            ConversationState state,
            RecommendationRequest request,
            NavigationRecommendationResult result) {
        synchronized (state) {
            if (!matchesRecommendation(state, request)
                    || state.phase == ConversationPhase.COMPLETED) {
                log.info(
                        "recommendation_discarded conversationId={} locationRevision={} clinicalRevision={} reason=STALE_REVISION",
                        state.conversationId,
                        request.locationRevision(),
                        request.clinicalRevision());
                return;
            }
            state.recommendationResult = result;
            state.recommendationStatus = result.status();
            state.recommendationErrorCode = null;
            state.recommendationSubscription = null;
            if (result.verificationRounds() > 0) {
                publishRecommendationEvent(
                        state,
                        request.locationTaskId(),
                        LOCAL_REPAIR_PROGRESS,
                        Map.of(
                                "rounds", result.verificationRounds(),
                                "maxRounds", 3,
                                "scope", "FAILED_ITEMS_ONLY"));
            }
            if (!result.findings().isEmpty()) {
                publishRecommendationEvent(
                        state,
                        request.locationTaskId(),
                        VALIDATION_FINDING,
                        Map.of("stage", "FINAL_VERIFICATION", "findings", result.findings()));
            }
            persistRecommendation(state, request.locationTaskId(), result);
            publishRecommendationEvent(
                    state,
                    request.locationTaskId(),
                    RECOMMENDATION_RESULT,
                    Map.of("result", result));
            publishRecommendationEvent(
                    state,
                    request.locationTaskId(),
                    PIPELINE_PROGRESS,
                    Map.of("stage", "RECOMMENDATION", "status", "COMPLETED"));
        }
        log.info(
                "recommendation_committed conversationId={} locationRevision={} clinicalRevision={} status={} diseases={} verificationRounds={} semanticTriggered={}",
                state.conversationId,
                request.locationRevision(),
                request.clinicalRevision(),
                result.status(),
                result.diseases().size(),
                result.verificationRounds(),
                result.semanticVerificationTriggered());
    }

    private void failRecommendation(
            ConversationState state, RecommendationRequest request, Throwable error) {
        synchronized (state) {
            if (!matchesRecommendation(state, request)) return;
            state.recommendationStatus = RecommendationStatus.FAILED;
            state.recommendationErrorCode = "RECOMMENDATION_PIPELINE_FAILED";
            state.recommendationSubscription = null;
            publishRecommendationEvent(
                    state,
                    request.locationTaskId(),
                    VALIDATION_FINDING,
                    Map.of(
                            "stage", "FINAL_VERIFICATION",
                            "code", "RECOMMENDATION_PIPELINE_FAILED",
                            "recoverable", true));
        }
        log.warn(
                "recommendation_failed conversationId={} locationRevision={} clinicalRevision={} errorType={}",
                state.conversationId,
                request.locationRevision(),
                request.clinicalRevision(),
                error.getClass().getSimpleName());
    }

    private boolean matchesRecommendation(
            ConversationState state, RecommendationRequest request) {
        return request.locationTaskId().equals(state.locationTaskId)
                && request.locationRevision() == state.locationRevision
                && request.clinicalRevision() == state.clinicalRevision
                && (request.locationRevision() + ":" + request.clinicalRevision())
                        .equals(state.recommendationRevisions);
    }

    private void publishRecommendationEvent(
            ConversationState state, UUID taskId, String type, Map<String, Object> data) {
        state.events.tryEmitNext(event(
                state,
                taskId,
                state.locationEventSequence,
                type,
                null,
                null,
                null,
                data));
    }

    private void publishLocationStatus(
            ConversationState state, UUID taskId, String status, boolean manualLocationRequired) {
        Map<String, Object> data = new LinkedHashMap<>();
        synchronized (state) {
            data.put("contextVersion", state.contextVersion);
            data.put("locationRevision", state.locationRevision);
            data.put("clinicalRevision", state.clinicalRevision);
            data.put("status", status);
            data.put("manualLocationRequired", manualLocationRequired);
            if (state.locationRoutingResult != null) {
                LocationResult location = state.locationRoutingResult.location();
                data.put("formattedAddress", location.formattedAddress());
                data.put("city", location.city());
                data.put("state", location.state());
                if (location.postalCode() != null) {
                    data.put("postalCode", location.postalCode());
                }
                data.put("source", location.source().name());
            }
            if (state.locationErrorCode != null) {
                data.put("errorCode", state.locationErrorCode);
            }
        }
        publishLocationEvent(state, taskId, LOCATION_STATUS, Map.copyOf(data));
    }

    private void publishLocationEvent(
            ConversationState state, UUID taskId, String type, Map<String, Object> data) {
        AtomicLong sequence;
        synchronized (state) {
            sequence = state.locationEventSequence;
        }
        state.events.tryEmitNext(event(
                state, taskId, sequence, type, null, null, null, data));
    }

    private Mono<AgentRunResult> executeRun(
            ConversationState state, UUID runId, String message) {
        return switch (state.phase) {
            case EXPECTING_USERNAME -> localRun(state, runId, () -> handleUsername(state, runId, message));
            case WAITING_SECRET -> localRun(state, runId, () -> publishTextRun(
                    state,
                    runId,
                    "请使用对话中出现的安全密码框继续。为保护隐私，聊天消息不会被当作密码处理。",
                    List.of()));
            case COLLECTING_PROFILE -> localRun(
                    state, runId, () -> handleProfileAnswer(state, runId, message));
            case READY -> handleReadyRun(state, runId, message);
            case COMPLETED -> localRun(state, runId, () -> publishTextRun(
                    state, runId, "这个 Session 已结束，请创建新会话继续。", List.of()));
        };
    }

    private Mono<AgentRunResult> localRun(
            ConversationState state, UUID runId, Runnable operation) {
        return Mono.fromCallable(() -> {
                    operation.run();
                    return new AgentRunResult(runId, "COMPLETED");
                })
                .onErrorResume(ApiException.class, exception -> {
                    publishTextRun(state, runId, exception.getMessage(), List.of());
                    return Mono.just(new AgentRunResult(runId, "REJECTED"));
                });
    }

    private void handleUsername(ConversationState state, UUID runId, String message) {
        UsernameLookup lookup = authService.lookupUsername(message);
        AuthPurpose purpose = lookup.exists() ? AuthPurpose.LOGIN : AuthPurpose.REGISTER;
        UUID requestId = UUID.randomUUID();
        state.pendingAuth = new PendingAuth(requestId, purpose, lookup.username());
        state.phase = ConversationPhase.WAITING_SECRET;

        String response = lookup.exists()
                ? "已找到用户名 “" + lookup.username() + "”。请在安全密码框中输入密码登录。"
                : "没有找到用户名 “" + lookup.username() + "”。请设置密码完成注册。";
        Map<String, Object> data = Map.of(
                "requestId", requestId.toString(),
                "purpose", purpose.name(),
                "username", lookup.username());
        publishTextRun(state, runId, response, List.of(new StructuredEvent(AUTH_SECRET_REQUEST, data)));
    }

    private AuthConversationResponse authenticate(
            ConversationState state,
            UUID runId,
            AuthPurpose submittedPurpose,
            AuthSecretSubmission request) {
        PendingAuth pending = state.pendingAuth;
        if (state.phase != ConversationPhase.WAITING_SECRET
                || pending == null
                || !pending.requestId.equals(request.requestId())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "AUTH_REQUEST_EXPIRED",
                    "The password request is no longer active.");
        }
        if (pending.purpose != submittedPurpose) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "AUTH_PURPOSE_MISMATCH",
                    "The password request does not match this authentication operation.");
        }

        AuthenticatedUser authenticated = submittedPurpose == AuthPurpose.REGISTER
                ? authService.register(pending.username, request.password())
                : authService.login(pending.username, request.password());

        state.pendingAuth = null;
        state.userId = authenticated.userId();
        state.username = authenticated.username();
        state.newUser = authenticated.newUser();
        UserMemorySnapshot persisted = memoryService.initializeSession(
                authenticated.userId(), state.sessionId.toString());
        state.persistedProfile.clear();
        state.persistedProfile.putAll(persisted.profile());
        List<ConversationMessageRow> pendingMessages;
        synchronized (state) {
            pendingMessages = List.copyOf(state.pendingMessages);
            state.pendingMessages.clear();
        }
        conversationHistoryService.bindSession(
                state.userId,
                state.sessionId.toString(),
                state.conversationId.toString(),
                state.createdAt,
                pendingMessages);

        String response;
        if (authenticated.newUser()) {
            state.phase = ConversationPhase.COLLECTING_PROFILE;
            response = "注册并登录成功。"
                    + profileExtractionService.questionFor(
                            profileExtractionService.missingFields(state.effectiveProfile()), 1);
        } else {
            state.phase = ConversationPhase.READY;
            response = "欢迎回来。请描述你这次需要咨询的症状；如果基本资料需要补充或更新，也可以一起告诉我。";
        }

        Map<String, Object> data = Map.of(
                "authenticated", true,
                "userId", authenticated.userId(),
                "username", authenticated.username(),
                "newUser", authenticated.newUser());
        publishTextRun(state, runId, response, List.of(new StructuredEvent(AUTH_RESULT, data)));
        return new AuthConversationResponse(
                runId,
                state.conversationId,
                authenticated.userId(),
                authenticated.username(),
                authenticated.newUser(),
                "AUTHENTICATED");
    }

    private void handleProfileAnswer(ConversationState state, UUID runId, String message) {
        patchExplicitMemoryFacts(state, message);
        Map<String, Object> effective = memoryService
                .effective(state.userId, state.sessionId.toString())
                .profile();
        ProfileExtractionResult extraction = profileExtractionService.extract(message, effective);
        state.profilePatch.putAll(extraction.values());
        if (!extraction.values().isEmpty()) {
            memoryService.patchProfile(
                    state.userId, state.sessionId.toString(), extraction.values());
        }
        state.profileRound++;

        Map<String, Object> updated = state.effectiveProfile();
        List<String> missing = profileExtractionService.missingFields(updated);
        boolean complete = missing.isEmpty() || extraction.skipRemaining() || state.profileRound >= 3;
        if (complete) {
            state.phase = ConversationPhase.READY;
        }

        StringBuilder response = new StringBuilder();
        if (!extraction.values().isEmpty()) {
            response.append("已记录：")
                    .append(String.join("、", extraction.values().keySet()))
                    .append("。 ");
        }
        if (!extraction.validationErrors().isEmpty()) {
            response.append("以下内容未通过校验：")
                    .append(String.join("；", extraction.validationErrors()))
                    .append("。 ");
        }
        if (complete) {
            if (!missing.isEmpty()) {
                response.append("画像采集已按三轮/跳过规则结束，未提供字段保持为空。 ");
            }
            response.append("现在请描述你需要咨询的症状。画像将在 Session 结束时持久化。");
        } else {
            response.append(profileExtractionService.questionFor(missing, state.profileRound + 1));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capturedFields", List.copyOf(extraction.values().keySet()));
        data.put("missingFields", missing);
        data.put("round", state.profileRound);
        data.put("complete", complete);
        publishTextRun(
                state,
                runId,
                response.toString().strip(),
                List.of(new StructuredEvent(PROFILE_UPDATED, Map.copyOf(data))));
    }

    private Mono<AgentRunResult> handleReadyRun(
            ConversationState state, UUID runId, String message) {
        state.displayLanguage = DisplayLanguage.resolve(message, state.displayLanguage);
        patchExplicitMemoryFacts(state, message);
        ProfileExtractionResult extraction = profileExtractionService.extract(message, Map.of());
        if (!extraction.values().isEmpty()) {
            state.profilePatch.putAll(extraction.values());
            memoryService.patchProfile(
                    state.userId, state.sessionId.toString(), extraction.values());
        }

        EffectiveMemory effectiveMemory =
                memoryService.effective(state.userId, state.sessionId.toString());

        AtomicLong sequence = new AtomicLong();
        String messageId = UUID.randomUUID().toString();
        state.events.tryEmitNext(event(
                state, runId, sequence, RUN_STARTED, messageId, null, null, null));
        state.events.tryEmitNext(event(
                state,
                runId,
                sequence,
                PIPELINE_PROGRESS,
                null,
                null,
                null,
                Map.of("stage", "CLINICAL_REASONING", "status", "RUNNING")));

        ClinicalAssessmentRequest request = new ClinicalAssessmentRequest(
                state.sessionId.toString(),
                state.userId,
                message,
                state.symptomCase,
                state.askedClinicalQuestions,
                state.clinicalQuestionCount,
                effectiveMemory.profile(),
                effectiveMemory.diseaseHistory(),
                effectiveMemory.coreConstraints(),
                effectiveMemory.history(),
                state.displayLanguage);
        return clinicalPipelineService.assess(request)
                .map(decision -> {
                    applyClinicalDecision(state, decision);
                    List<StructuredEvent> structuredEvents = new ArrayList<>();
                    if (!extraction.values().isEmpty()) {
                        structuredEvents.add(new StructuredEvent(
                                PROFILE_UPDATED,
                                Map.of(
                                        "capturedFields", List.copyOf(extraction.values().keySet()),
                                        "missingFields", profileExtractionService.missingFields(
                                                state.effectiveProfile()),
                                        "round", state.profileRound,
                                        "complete", true)));
                    }
                    structuredEvents.add(new StructuredEvent(
                            decision.decision() == ClinicalReadiness.ASK_USER
                                    ? CLINICAL_QUESTION
                                    : CLINICAL_RESULT,
                            publicClinicalData(state, decision)));
                    structuredEvents.add(new StructuredEvent(
                            PIPELINE_PROGRESS,
                            Map.of("stage", "CLINICAL_REASONING", "status", "COMPLETED")));
                    synchronized (state) {
                        state.clinicalPublishedRevision = state.clinicalRevision;
                        Map<String, Object> joinData = claimJoinData(state);
                        if (joinData != null) {
                            structuredEvents.add(
                                    new StructuredEvent(NAVIGATION_CONTEXT_READY, joinData));
                        }
                        publishTextRunTail(
                                state,
                                runId,
                                messageId,
                                sequence,
                                clinicalResponse(decision, state.displayLanguage),
                                structuredEvents);
                    }
                    startRecommendationIfReady(state);
                    log.info(
                            "clinical_run_completed conversationId={} runId={} decision={} questionCount={} hypotheses={}",
                            state.conversationId,
                            runId,
                            decision.decision(),
                            state.clinicalQuestionCount,
                            decision.hypotheses().size());
                    return new AgentRunResult(runId, "COMPLETED");
                })
                .onErrorResume(
                        error -> error instanceof ClinicalValidationException
                                || error instanceof ClinicalModelUnavailableException,
                        error -> {
                            String code = error instanceof ClinicalValidationException
                                    ? "CLINICAL_OUTPUT_INVALID"
                                    : "CLINICAL_MODEL_UNAVAILABLE";
                            preserveFailedClinicalInput(state, message);
                            publishTextRunTail(
                                    state,
                                    runId,
                                    messageId,
                                    sequence,
                                    DisplayLanguage.isChinese(state.displayLanguage)
                                            ? "本次症状分析暂时无法完成，已保留你提供的信息。请稍后重新发送或补充症状。"
                                            : "The symptom analysis could not be completed. Your information has been retained; please retry or add more symptom details.",
                                    List.of(
                                            new StructuredEvent(
                                                    VALIDATION_FINDING,
                                                    Map.of(
                                                            "stage", "CLINICAL_REASONING",
                                                            "code", code,
                                                            "recoverable", true)),
                                            new StructuredEvent(
                                                    PIPELINE_PROGRESS,
                                                    Map.of(
                                                            "stage", "CLINICAL_REASONING",
                                                            "status", "DEGRADED"))));
                            log.warn(
                                    "clinical_run_degraded conversationId={} runId={} code={} errorType={}",
                                    state.conversationId,
                                    runId,
                                    code,
                                    error.getClass().getSimpleName());
                            return Mono.just(new AgentRunResult(runId, "DEGRADED"));
                        });
    }

    private void preserveFailedClinicalInput(ConversationState state, String message) {
        SymptomCase preserved = memoryService.preserveClinicalInput(
                state.userId, state.sessionId.toString(), message);
        synchronized (state) {
            state.symptomCase = preserved;
            state.latestClinicalDecision = null;
            state.clinicalRevision++;
            state.contextVersion++;
            state.lastJoinedRevisions = null;
            invalidateRecommendation(state);
        }
    }

    private void patchExplicitMemoryFacts(ConversationState state, String message) {
        ExplicitMemoryFacts facts = explicitMemoryFactExtractionService.extract(message);
        if (!facts.isEmpty()) {
            memoryService.patchExplicitFacts(
                    state.userId, state.sessionId.toString(), facts);
            log.info(
                    "explicit_memory_facts_captured sessionId={} diseaseHistoryCount={} constraintCategories={}",
                    state.sessionId,
                    facts.diseaseHistory().size(),
                    facts.coreConstraints().keySet());
        }
    }

    private void applyClinicalDecision(ConversationState state, ClinicalDecision decision) {
        synchronized (state) {
            state.symptomCase = decision.symptomCase();
            state.latestClinicalDecision = decision;
            state.clinicalRevision++;
            state.contextVersion++;
            state.lastJoinedRevisions = null;
            invalidateRecommendation(state);
            if (decision.decision() == ClinicalReadiness.ASK_USER) {
                state.clinicalQuestionCount++;
                state.askedClinicalQuestions.add(decision.proposedQuestion());
            }
        }
        memoryService.updateClinical(state.userId, state.sessionId.toString(), decision);
    }

    private Map<String, Object> claimJoinData(ConversationState state) {
        synchronized (state) {
            if (!isJoinReady(state)) {
                return null;
            }
            String revisions = state.locationRevision + ":" + state.clinicalRevision;
            if (revisions.equals(state.lastJoinedRevisions)) {
                return null;
            }
            state.lastJoinedRevisions = revisions;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("contextVersion", state.contextVersion);
            data.put("locationRevision", state.locationRevision);
            data.put("clinicalRevision", state.clinicalRevision);
            data.put(
                    "hospitalCount",
                    state.locationRoutingResult.routeMatrix().hospitalCount());
            data.put(
                    "routeElementCount",
                    state.locationRoutingResult.routeMatrix().routeElementCount());
            if (state.locationRoutingResult.routeMatrix().snapshotId() != null) {
                data.put(
                        "cmsSnapshotId",
                        state.locationRoutingResult.routeMatrix().snapshotId());
            }
            data.put("status", "READY");
            return Map.copyOf(data);
        }
    }

    private boolean isJoinReady(ConversationState state) {
        if (state.locationStatus != LocationStatus.READY
                || state.locationRoutingResult == null
                || state.latestClinicalDecision == null
                || state.clinicalPublishedRevision != state.clinicalRevision) {
            return false;
        }
        return state.latestClinicalDecision.decision() == ClinicalReadiness.READY
                || state.latestClinicalDecision.decision() == ClinicalReadiness.READY_LIMITED;
    }

    private Map<String, Object> publicClinicalData(
            ConversationState state, ClinicalDecision decision) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision", decision.decision().name());
        data.put("readinessConfidence", decision.readinessConfidence());
        data.put("questionCount", state.clinicalQuestionCount);
        data.put("maxQuestions", 3);
        data.put("revision", state.clinicalRevision);
        data.put("contextVersion", state.contextVersion);
        data.put("symptomCase", decision.symptomCase());
        data.put("missingInformation", decision.missingInformation());
        if (decision.proposedQuestion() != null) {
            data.put("question", decision.proposedQuestion());
        }
        data.put("hypotheses", decision.hypotheses().stream()
                .map(this::publicHypothesis)
                .toList());
        return Map.copyOf(data);
    }

    private Map<String, Object> publicHypothesis(DiseaseHypothesis hypothesis) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", hypothesis.name());
        result.put("severity", hypothesis.severity().name());
        result.put("estimatedProbability", hypothesis.estimatedProbability());
        result.put("supportingFacts", hypothesis.supportingFacts());
        result.put("limitations", hypothesis.limitations());
        return Map.copyOf(result);
    }

    private String clinicalResponse(ClinicalDecision decision, String displayLanguage) {
        if (!DisplayLanguage.isChinese(displayLanguage)) {
            return englishClinicalResponse(decision);
        }
        if (decision.decision() == ClinicalReadiness.ASK_USER) {
            return decision.proposedQuestion();
        }
        if (decision.decision() == ClinicalReadiness.INSUFFICIENT_INFORMATION) {
            return "在三轮追问后，现有信息仍不足以形成可信的初步疾病假设。本次不会生成疾病或医院推荐；你可以继续补充主要症状、持续时间和严重程度后重新评估。";
        }

        StringBuilder response = new StringBuilder();
        if (decision.decision() == ClinicalReadiness.READY_LIMITED) {
            response.append("以下是基于有限信息形成的初步导诊假设，存在较大不确定性。\n");
        } else {
            response.append("根据你目前提供的信息，形成以下初步导诊假设（不是医学诊断）：\n");
        }
        for (int index = 0; index < decision.hypotheses().size(); index++) {
            DiseaseHypothesis hypothesis = decision.hypotheses().get(index);
            response.append(index + 1)
                    .append(". ")
                    .append(hypothesis.name())
                    .append("｜严重程度 ")
                    .append(chineseSeverity(hypothesis.severity()))
                    .append("｜估计概率 ")
                    .append(Math.round(hypothesis.estimatedProbability() * 100))
                    .append("%\n   依据：")
                    .append(String.join("；", hypothesis.supportingFacts()))
                    .append('\n');
        }
        response.append("信息完整度置信度：")
                .append(Math.round(decision.readinessConfidence() * 100))
                .append("%。你继续提供新症状时，我会创建新的 Run 并重新评估。"
                        + "定位与路线会在后台并行处理；两路就绪后会生成疾病—医院—医生三级推荐。");
        return response.toString();
    }

    private String englishClinicalResponse(ClinicalDecision decision) {
        if (decision.decision() == ClinicalReadiness.ASK_USER) {
            return decision.proposedQuestion();
        }
        if (decision.decision() == ClinicalReadiness.INSUFFICIENT_INFORMATION) {
            return "After three follow-up questions, there is still not enough information to form reliable preliminary hypotheses. No disease or hospital recommendations will be generated. You may add the main symptom, duration, and severity for reassessment.";
        }
        StringBuilder response = new StringBuilder(decision.decision() == ClinicalReadiness.READY_LIMITED
                ? "These preliminary navigation possibilities are based on limited information and remain uncertain.\n"
                : "Based on what you shared, these are preliminary navigation possibilities, not diagnoses:\n");
        for (int index = 0; index < decision.hypotheses().size(); index++) {
            DiseaseHypothesis hypothesis = decision.hypotheses().get(index);
            response.append(index + 1)
                    .append(". ")
                    .append(hypothesis.name())
                    .append(" | Severity: ")
                    .append(hypothesis.severity().name().toLowerCase())
                    .append(" | Estimated likelihood within this candidate set: ")
                    .append(Math.round(hypothesis.estimatedProbability() * 100))
                    .append("%\n   Supporting details: ")
                    .append(String.join("; ", hypothesis.supportingFacts()))
                    .append('\n');
        }
        response.append("Information completeness: ")
                .append(Math.round(decision.readinessConfidence() * 100))
                .append("%. New symptoms will start a new assessment Run. Location and route lookup continue in parallel; the disease–hospital–doctor recommendations appear after both branches are ready.");
        return response.toString();
    }

    private String chineseSeverity(com.example.mednav.clinical.DiseaseSeverity severity) {
        return switch (severity) {
            case MILD -> "较轻";
            case MODERATE -> "中等";
            case SEVERE -> "较重";
        };
    }

    private Mono<ConversationCompletionResponse> completeSession(
            ConversationState state, boolean idleFinalization) {
        if (state.userId == null) {
            return Mono.error(new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Please sign in before ending the Session."));
        }
        if (state.phase == ConversationPhase.COMPLETED) {
            return Mono.just(new ConversationCompletionResponse(
                    state.conversationId,
                    state.sessionId,
                    "COMPLETED",
                    false,
                    state.completedAt));
        }
        state.finalizationRequested = true;
        return memoryFinalizer
                .finalizeSession(state.userId, state.sessionId.toString(), state.createdAt)
                .map(result -> commitCompletedSession(state, result, idleFinalization))
                .doOnError(error -> state.finalizationRequested = false);
    }

    private ConversationCompletionResponse commitCompletedSession(
            ConversationState state,
            SessionFinalizationResult result,
            boolean idleFinalization) {
        state.persistedProfile.clear();
        state.persistedProfile.putAll(memoryService.refreshUser(state.userId).profile());
        state.profilePatch.clear();
        synchronized (state) {
            cancelLocationTask(state);
            invalidateRecommendation(state);
            state.phase = ConversationPhase.COMPLETED;
            state.completedAt = Instant.now();
        }
        conversationHistoryService.complete(
                state.sessionId.toString(), state.completedAt, idleFinalization);
        UUID runId = UUID.randomUUID();
        String content = idleFinalization
                ? "Session 因空闲达到 10 分钟已自动结束，Memory 已保存。"
                : result.profilePersisted()
                        ? "Session 已结束，画像和本次症状 Memory 已保存。"
                        : "Session 已结束，本次症状 Memory 已保存。";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profilePersisted", result.profilePersisted());
        data.put("clinicalPersisted", result.clinicalPersisted());
        data.put("summaryPersisted", result.summaryPersisted());
        data.put("summaryDegraded", result.summaryDegraded());
        data.put("idleFinalization", idleFinalization);
        publishTextRun(
                state,
                runId,
                content,
                List.of(new StructuredEvent(SESSION_COMPLETED, Map.copyOf(data))));
        return new ConversationCompletionResponse(
                state.conversationId,
                state.sessionId,
                "COMPLETED",
                result.profilePersisted(),
                state.completedAt);
    }

    @Scheduled(fixedDelayString = "${mednav.memory.idle-scan-ms:60000}")
    public void finalizeIdleSessions() {
        Instant cutoff = Instant.now().minus(idleTimeout);
        for (ConversationState state : conversations.values()) {
            synchronized (state) {
                if (state.userId == null
                        || state.phase == ConversationPhase.COMPLETED
                        || state.finalizationRequested
                        || state.lastActivityAt.isAfter(cutoff)) {
                    continue;
                }
                state.finalizationRequested = true;
            }
            scheduler.schedule(
                            state.sessionId.toString(),
                            () -> completeSession(state, true))
                    .subscribe(
                            ignored -> log.info(
                                    "idle_session_finalized conversationId={} sessionId={}",
                                    state.conversationId,
                                    state.sessionId),
                            error -> {
                                state.finalizationRequested = false;
                                log.warn(
                                        "idle_session_finalization_failed conversationId={} sessionId={} errorType={}",
                                        state.conversationId,
                                        state.sessionId,
                                        error.getClass().getSimpleName());
                            });
        }
    }

    private void touch(ConversationState state) {
        state.lastActivityAt = Instant.now();
        if (state.userId != null) {
            conversationHistoryService.touch(state.sessionId.toString(), state.lastActivityAt);
        }
    }

    private void publishTextRun(
            ConversationState state,
            UUID runId,
            String content,
            List<StructuredEvent> structuredEvents) {
        String messageId = UUID.randomUUID().toString();
        AtomicLong sequence = new AtomicLong();
        List<AgentEvent> events = new ArrayList<>();
        events.add(event(state, runId, sequence, RUN_STARTED, messageId, null, null, null));
        events.add(event(state, runId, sequence, TEXT_MESSAGE_START, messageId, null, null, null));
        events.add(event(state, runId, sequence, TEXT_MESSAGE_CONTENT, messageId, content, null, null));
        events.add(event(state, runId, sequence, TEXT_MESSAGE_END, messageId, null, content, null));
        for (StructuredEvent structured : structuredEvents) {
            events.add(event(
                    state,
                    runId,
                    sequence,
                    structured.type,
                    null,
                    null,
                    null,
                    structured.data));
        }
        events.add(event(state, runId, sequence, RUN_FINISHED, messageId, null, null, null));
        events.forEach(state.events::tryEmitNext);
        persistMessage(state, messageId, runId, "ASSISTANT", content);
    }

    private void publishTextRunTail(
            ConversationState state,
            UUID runId,
            String messageId,
            AtomicLong sequence,
            String content,
            List<StructuredEvent> structuredEvents) {
        state.events.tryEmitNext(event(
                state, runId, sequence, TEXT_MESSAGE_START, messageId, null, null, null));
        state.events.tryEmitNext(event(
                state, runId, sequence, TEXT_MESSAGE_CONTENT, messageId, content, null, null));
        state.events.tryEmitNext(event(
                state, runId, sequence, TEXT_MESSAGE_END, messageId, null, content, null));
        for (StructuredEvent structured : structuredEvents) {
            state.events.tryEmitNext(event(
                    state,
                    runId,
                    sequence,
                    structured.type,
                    null,
                    null,
                    null,
                    structured.data));
        }
        state.events.tryEmitNext(event(
                state, runId, sequence, RUN_FINISHED, messageId, null, null, null));
        persistMessage(state, messageId, runId, "ASSISTANT", content);
    }

    private void persistMessage(
            ConversationState state,
            String messageId,
            UUID runId,
            String role,
            String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        ConversationMessageRow message = new ConversationMessageRow(
                messageId,
                state.sessionId.toString(),
                runId == null ? null : runId.toString(),
                state.messageSequence.incrementAndGet(),
                role,
                content,
                "TEXT",
                null,
                Instant.now());
        synchronized (state) {
            if (state.userId == null) {
                state.pendingMessages.add(message);
                return;
            }
        }
        conversationHistoryService.append(message);
    }

    private void persistRecommendation(
            ConversationState state,
            UUID runId,
            NavigationRecommendationResult result) {
        ConversationMessageRow message = new ConversationMessageRow(
                UUID.randomUUID().toString(),
                state.sessionId.toString(),
                runId.toString(),
                state.messageSequence.incrementAndGet(),
                "ASSISTANT",
                "已生成疾病—医院—医生推荐结果。",
                "RECOMMENDATION_RESULT",
                conversationHistoryService.serializePayload(result),
                Instant.now());
        conversationHistoryService.append(message);
    }

    private ConversationSessionRow requireOwnedSession(String userId, UUID sessionId) {
        ConversationSessionRow row =
                conversationHistoryService.ownedSession(userId, sessionId.toString());
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found.");
        }
        return row;
    }

    private boolean isResumable(
            ConversationSessionRow row, String userId, Instant cutoff) {
        ConversationState state = sessions.get(UUID.fromString(row.sessionId()));
        return "ACTIVE".equals(row.status())
                && state != null
                && userId.equals(state.userId)
                && state.phase != ConversationPhase.COMPLETED
                && !state.finalizationRequested
                && state.lastActivityAt.isAfter(cutoff);
    }

    private AgentEvent event(
            ConversationState state,
            UUID runId,
            AtomicLong sequence,
            String type,
            String messageId,
            String delta,
            String content,
            Map<String, Object> data) {
        return new AgentEvent(
                UUID.randomUUID(),
                type,
                state.conversationId,
                state.sessionId,
                runId,
                messageId,
                sequence.incrementAndGet(),
                Instant.now(),
                delta,
                content,
                null,
                data);
    }

    private ConversationState requireConversation(UUID conversationId) {
        ConversationState state = conversations.get(conversationId);
        if (state == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "CONVERSATION_NOT_FOUND",
                    "Conversation does not exist: " + conversationId);
        }
        return state;
    }

    private ConversationState requireAuthenticatedConversation(UUID conversationId) {
        ConversationState state = requireConversation(conversationId);
        if (state.userId == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "This conversation is not authenticated.");
        }
        return state;
    }

    private void publishRunError(ConversationState state, UUID runId, Throwable error) {
        String code = error instanceof ApiException apiException
                ? apiException.code()
                : "AGENT_RUN_FAILED";
        state.events.tryEmitNext(new AgentEvent(
                UUID.randomUUID(),
                AgentEventType.RUN_ERROR,
                state.conversationId,
                state.sessionId,
                runId,
                null,
                Long.MAX_VALUE,
                Instant.now(),
                null,
                "Run failed",
                code));
    }

    private enum ConversationPhase {
        EXPECTING_USERNAME,
        WAITING_SECRET,
        COLLECTING_PROFILE,
        READY,
        COMPLETED
    }

    private record PendingAuth(UUID requestId, AuthPurpose purpose, String username) {}

    private record StructuredEvent(String type, Map<String, Object> data) {}

    private static final class ConversationState {
        private final UUID conversationId;
        private final UUID sessionId;
        private final Instant createdAt;
        private final Sinks.Many<AgentEvent> events;
        private final Map<String, Object> persistedProfile = new LinkedHashMap<>();
        private final Map<String, Object> profilePatch = new LinkedHashMap<>();
        private final List<String> askedClinicalQuestions = new ArrayList<>();
        private final List<ConversationMessageRow> pendingMessages = new ArrayList<>();
        private final AtomicLong messageSequence = new AtomicLong();
        private volatile ConversationPhase phase = ConversationPhase.EXPECTING_USERNAME;
        private volatile PendingAuth pendingAuth;
        private volatile String userId;
        private volatile String username;
        private volatile boolean newUser;
        private int profileRound;
        private int clinicalQuestionCount;
        private long contextVersion;
        private long clinicalRevision;
        private long clinicalPublishedRevision;
        private long locationRevision;
        private volatile SymptomCase symptomCase;
        private volatile ClinicalDecision latestClinicalDecision;
        private volatile UUID locationTaskId;
        private volatile LocationStatus locationStatus = LocationStatus.NOT_REQUESTED;
        private volatile LocationRoutingResult locationRoutingResult;
        private volatile String locationErrorCode;
        private volatile String lastJoinedRevisions;
        private volatile Disposable locationSubscription;
        private volatile AtomicLong locationEventSequence = new AtomicLong();
        private volatile RecommendationStatus recommendationStatus = RecommendationStatus.WAITING;
        private volatile NavigationRecommendationResult recommendationResult;
        private volatile String recommendationErrorCode;
        private volatile String recommendationRevisions;
        private volatile Disposable recommendationSubscription;
        private volatile Instant completedAt;
        private volatile Instant lastActivityAt;
        private volatile boolean finalizationRequested;
        private volatile String displayLanguage = DisplayLanguage.ZH_CN;

        private ConversationState(
                UUID conversationId,
                UUID sessionId,
                Instant createdAt,
                Sinks.Many<AgentEvent> events) {
            this.conversationId = conversationId;
            this.sessionId = sessionId;
            this.createdAt = createdAt;
            this.lastActivityAt = createdAt;
            this.events = events;
        }

        private Map<String, Object> effectiveProfile() {
            Map<String, Object> result = new LinkedHashMap<>(persistedProfile);
            result.putAll(profilePatch);
            return result;
        }
    }
}
