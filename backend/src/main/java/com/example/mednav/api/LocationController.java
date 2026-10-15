package com.example.mednav.api;

import com.example.mednav.runtime.ConversationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LocationController {

    private final ConversationService conversationService;

    public LocationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/location/consent")
    public ResponseEntity<LocationTaskAcceptedResponse> consent(
            @Valid @RequestBody LocationConsentRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(conversationService.submitLocationConsent(request));
    }

    @PostMapping("/location/manual")
    public ResponseEntity<LocationTaskAcceptedResponse> manual(
            @Valid @RequestBody ManualLocationRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(conversationService.submitManualLocation(request));
    }

    @GetMapping("/navigation/runs/{taskId}")
    public NavigationRunResponse navigationRun(
            @PathVariable UUID taskId,
            @RequestHeader("X-Conversation-Id") UUID conversationId) {
        return conversationService.navigationRun(conversationId, taskId);
    }

    @GetMapping("/navigation/runs/{taskId}/results")
    public RecommendationRunResponse recommendationRun(
            @PathVariable UUID taskId,
            @RequestHeader("X-Conversation-Id") UUID conversationId) {
        return conversationService.recommendationRun(conversationId, taskId);
    }
}
