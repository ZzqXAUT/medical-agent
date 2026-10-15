package com.example.mednav.api;

import com.example.mednav.auth.AuthPurpose;
import com.example.mednav.runtime.ConversationService;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api")
public class AuthConversationController {

    private final ConversationService conversationService;

    public AuthConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping(
            value = "/auth/conversation/login",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<AuthConversationResponse> login(@Valid @RequestBody AuthSecretSubmission request) {
        return conversationService.submitSecret(AuthPurpose.LOGIN, request);
    }

    @PostMapping(
            value = "/auth/conversation/register",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<AuthConversationResponse> register(@Valid @RequestBody AuthSecretSubmission request) {
        return conversationService.submitSecret(AuthPurpose.REGISTER, request);
    }

    @GetMapping("/users/me/profile")
    public Mono<UserProfileResponse> profile(
            @RequestHeader("X-Conversation-Id") UUID conversationId) {
        return conversationService.profile(conversationId);
    }

    @GetMapping("/users/me/session-memories")
    public Mono<List<SessionMemoryResponse>> sessionMemories(
            @RequestHeader("X-Conversation-Id") UUID conversationId) {
        return conversationService.sessionMemories(conversationId);
    }

    @DeleteMapping("/users/me/session-memories/{sessionId}")
    public Mono<ResponseEntity<Void>> deleteSessionMemory(
            @RequestHeader("X-Conversation-Id") UUID conversationId,
            @PathVariable UUID sessionId) {
        return conversationService
                .deleteSessionMemory(conversationId, sessionId)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/users/me/sessions")
    public Mono<ConversationResponse> createSession(
            @RequestHeader("X-Conversation-Id") UUID conversationId) {
        return blocking(() -> conversationService.createAuthenticatedSession(conversationId));
    }

    @GetMapping("/users/me/sessions")
    public Mono<List<UserSessionResponse>> sessions(
            @RequestHeader("X-Conversation-Id") UUID conversationId) {
        return blocking(() -> conversationService.userSessions(conversationId));
    }

    @GetMapping("/users/me/sessions/{sessionId}/messages")
    public Mono<List<ConversationMessageResponse>> sessionMessages(
            @RequestHeader("X-Conversation-Id") UUID conversationId,
            @PathVariable UUID sessionId) {
        return blocking(() -> conversationService.sessionMessages(conversationId, sessionId));
    }

    @PostMapping("/users/me/sessions/{sessionId}/resume")
    public Mono<ConversationResponse> resumeSession(
            @RequestHeader("X-Conversation-Id") UUID conversationId,
            @PathVariable UUID sessionId) {
        return blocking(() -> conversationService.resumeSession(conversationId, sessionId));
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
    }
}
