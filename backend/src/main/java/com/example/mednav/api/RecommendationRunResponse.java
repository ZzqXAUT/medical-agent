package com.example.mednav.api;

import com.example.mednav.recommendation.NavigationRecommendationResult;
import com.example.mednav.recommendation.RecommendationStatus;
import java.util.UUID;

public record RecommendationRunResponse(
        UUID taskId,
        UUID conversationId,
        long contextVersion,
        long locationRevision,
        long clinicalRevision,
        RecommendationStatus status,
        NavigationRecommendationResult result,
        String errorCode) {}
