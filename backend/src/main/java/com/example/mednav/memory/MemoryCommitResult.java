package com.example.mednav.memory;

public record MemoryCommitResult(
        boolean profilePersisted, boolean clinicalPersisted, boolean summaryPersisted) {}
