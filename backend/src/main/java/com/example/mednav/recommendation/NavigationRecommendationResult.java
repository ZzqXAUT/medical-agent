package com.example.mednav.recommendation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NavigationRecommendationResult(
        UUID conversationId,
        UUID locationTaskId,
        long contextVersion,
        long locationRevision,
        long clinicalRevision,
        String cmsSnapshotId,
        String displayLanguage,
        RecommendationStatus status,
        ConfidenceResult overallConfidence,
        int verificationRounds,
        boolean semanticVerificationTriggered,
        List<VerificationFinding> findings,
        List<DiseaseRecommendation> diseases,
        List<EvidenceItem> evidence,
        Instant generatedAt) {

    public NavigationRecommendationResult {
        displayLanguage = com.example.mednav.i18n.DisplayLanguage.normalize(displayLanguage);
        findings = findings == null ? List.of() : List.copyOf(findings);
        diseases = diseases == null ? List.of() : List.copyOf(diseases);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
