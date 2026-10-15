package com.example.mednav.api;

import com.example.mednav.agui.AgentEvent;
import com.example.mednav.runtime.ConversationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public ResponseEntity<ConversationResponse> createConversation() {
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationService.createConversation());
    }

    @PostMapping("/{conversationId}/runs")
    public ResponseEntity<RunAcceptedResponse> createRun(
            @PathVariable UUID conversationId,
            @Valid @RequestBody CreateRunRequest request) {
        return ResponseEntity.accepted()
                .body(conversationService.submitRun(conversationId, request.message()));
    }

    @GetMapping(value = "/{conversationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> events(@PathVariable UUID conversationId) {
        return conversationService.events(conversationId)
                .map(event -> ServerSentEvent.<AgentEvent>builder()
                        .id(event.eventId().toString())
                        .event(event.type())
                        .data(event)
                        .build());
    }

    @PostMapping("/{conversationId}/complete")
    public Mono<ConversationCompletionResponse> complete(@PathVariable UUID conversationId) {
        return conversationService.completeConversation(conversationId);
    }
}
