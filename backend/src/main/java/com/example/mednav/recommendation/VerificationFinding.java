package com.example.mednav.recommendation;

public record VerificationFinding(
        String path,
        String code,
        VerificationSeverity severity,
        String message,
        String repairAction,
        boolean semantic) {}
