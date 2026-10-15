package com.example.mednav.recommendation;

public record EvidenceItem(
        String evidenceId,
        String source,
        String snapshotId,
        String entityId,
        String fact) {}
