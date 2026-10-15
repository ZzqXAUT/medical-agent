package com.example.mednav.recommendation;

import com.example.mednav.clinical.ClinicalDecision;
import com.example.mednav.location.LocationRoutingResult;
import java.util.UUID;

public record RecommendationRequest(
        UUID conversationId,
        UUID locationTaskId,
        long contextVersion,
        long locationRevision,
        long clinicalRevision,
        ClinicalDecision clinicalDecision,
        LocationRoutingResult locationRoutingResult,
        String displayLanguage) {

    public RecommendationRequest {
        displayLanguage = com.example.mednav.i18n.DisplayLanguage.normalize(displayLanguage);
    }

    public RecommendationRequest(
            UUID conversationId,
            UUID locationTaskId,
            long contextVersion,
            long locationRevision,
            long clinicalRevision,
            ClinicalDecision clinicalDecision,
            LocationRoutingResult locationRoutingResult) {
        this(
                conversationId,
                locationTaskId,
                contextVersion,
                locationRevision,
                clinicalRevision,
                clinicalDecision,
                locationRoutingResult,
                com.example.mednav.i18n.DisplayLanguage.ZH_CN);
    }
}
