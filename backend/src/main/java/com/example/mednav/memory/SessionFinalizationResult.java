package com.example.mednav.memory;

public record SessionFinalizationResult(
        boolean profilePersisted,
        boolean clinicalPersisted,
        boolean summaryPersisted,
        boolean alreadyFinalized,
        boolean summaryDegraded) {}
