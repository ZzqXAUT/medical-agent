package com.example.mednav.recommendation;

import java.util.List;

public record SemanticVerificationReport(List<VerificationFinding> findings) {
    public SemanticVerificationReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
