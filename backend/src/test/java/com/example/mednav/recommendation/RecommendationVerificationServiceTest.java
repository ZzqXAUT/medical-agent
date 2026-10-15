package com.example.mednav.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mednav.clinical.DiseaseSeverity;
import com.example.mednav.route.RouteMeasure;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RecommendationVerificationServiceTest {

    @Test
    void repairsOnlyInvalidBranchesWithinBudget() {
        AtomicInteger semanticCalls = new AtomicInteger();
        SemanticVerificationClient semantic = result -> {
            semanticCalls.incrementAndGet();
            return Mono.just(new SemanticVerificationReport(List.of()));
        };
        ConfidenceService confidenceService = new ConfidenceService();
        RecommendationVerificationService service = new RecommendationVerificationService(
                new RecommendationGate(), semantic, confidenceService, false, 65, 3);
        ConfidenceResult confidence =
                new ConfidenceResult(100, ConfidenceBand.HIGH, "test", Map.of("valid", 100));
        DoctorRecommendation validDoctor = doctor(
                "1000000001",
                "H1",
                List.of("cms:snapshot:doctor:1000000001", "cms:snapshot:affiliation:1000000001:H1"),
                confidence);
        DoctorRecommendation invalidDoctor = doctor(
                "bad-npi",
                "H1",
                List.of("cms:snapshot:doctor:1000000001", "cms:snapshot:affiliation:1000000001:H1"),
                confidence);
        HospitalRecommendation validHospital = hospital(
                "H1", "3.0", List.of(invalidDoctor, validDoctor, validDoctor), confidence);
        HospitalRecommendation outsideRange =
                hospital("H2", "6.0", List.of(), confidence);
        DiseaseRecommendation disease = new DiseaseRecommendation(
                new PublicDiseaseHypothesis(
                        "Moderate disease",
                        DiseaseSeverity.MODERATE,
                        0.8,
                        List.of("user fact"),
                        List.of()),
                1,
                "Disease recommendation paragraph.",
                confidence,
                List.of(outsideRange, validHospital),
                List.of("clinical"),
                List.of());
        NavigationRecommendationResult generated = new NavigationRecommendationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                2,
                1,
                1,
                "snapshot",
                "en-US",
                RecommendationStatus.READY,
                confidence,
                0,
                false,
                List.of(),
                List.of(disease),
                List.of(
                        new EvidenceItem("clinical", "USER_REPORTED", null, "user", "user fact"),
                        new EvidenceItem("hospital", "CMS", "snapshot", "H1", "hospital"),
                        new EvidenceItem("cms:snapshot:doctor:1000000001", "CMS", "snapshot", "1000000001", "doctor"),
                        new EvidenceItem("cms:snapshot:affiliation:1000000001:H1", "CMS", "snapshot", "1000000001", "affiliation")),
                Instant.now());

        NavigationRecommendationResult verified = service.verify(generated).block();

        assertThat(verified).isNotNull();
        assertThat(verified.verificationRounds()).isEqualTo(1);
        assertThat(verified.status()).isEqualTo(RecommendationStatus.READY);
        assertThat(verified.diseases().get(0).hospitals())
                .extracting(HospitalRecommendation::hospitalId)
                .containsExactly("H1");
        assertThat(verified.diseases().get(0).hospitals().get(0).doctors())
                .extracting(DoctorRecommendation::npi)
                .containsExactly("1000000001");
        assertThat(semanticCalls).hasValue(0);
    }

    @Test
    void callsSemanticHelperOnlyWhenRiskPolicyMatches() {
        AtomicInteger semanticCalls = new AtomicInteger();
        SemanticVerificationClient semantic = result -> {
            semanticCalls.incrementAndGet();
            return Mono.just(new SemanticVerificationReport(List.of(new VerificationFinding(
                    "$.diseases[0].hospitals[0]",
                    "SEMANTIC_REASON_REVIEW",
                    VerificationSeverity.WARNING,
                    "Review the low-confidence explanation.",
                    "REWRITE_REASON",
                    true))));
        };
        ConfidenceService confidenceService = new ConfidenceService();
        RecommendationVerificationService service = new RecommendationVerificationService(
                new RecommendationGate(), semantic, confidenceService, true, 65, 3);
        ConfidenceResult low =
                new ConfidenceResult(40, ConfidenceBand.LOW, "test", Map.of("valid", 40));
        HospitalRecommendation hospital = hospital("H1", "3.0", List.of(), low);
        DiseaseRecommendation disease = new DiseaseRecommendation(
                new PublicDiseaseHypothesis(
                        "Moderate disease",
                        DiseaseSeverity.MODERATE,
                        0.8,
                        List.of("user fact"),
                        List.of()),
                1,
                "Disease recommendation paragraph.",
                low,
                List.of(hospital),
                List.of("clinical"),
                List.of());
        NavigationRecommendationResult generated = new NavigationRecommendationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                2,
                1,
                1,
                "snapshot",
                "en-US",
                RecommendationStatus.READY,
                low,
                0,
                false,
                List.of(),
                List.of(disease),
                List.of(
                        new EvidenceItem("clinical", "USER_REPORTED", null, "user", "user fact"),
                        new EvidenceItem("hospital", "CMS", "snapshot", "H1", "hospital")),
                Instant.now());

        NavigationRecommendationResult verified = service.verify(generated).block();

        assertThat(semanticCalls).hasValue(1);
        assertThat(verified).isNotNull();
        assertThat(verified.semanticVerificationTriggered()).isTrue();
        assertThat(verified.status()).isEqualTo(RecommendationStatus.READY_WITH_WARNINGS);
        assertThat(verified.findings()).extracting(VerificationFinding::code)
                .containsExactly("SEMANTIC_REASON_REVIEW");
    }

    private HospitalRecommendation hospital(
            String id,
            String distance,
            List<DoctorRecommendation> doctors,
            ConfidenceResult confidence) {
        return new HospitalRecommendation(
                id,
                "Hospital " + id,
                1,
                4,
                "Address",
                "Hospital recommendation paragraph.",
                List.of("reason", "distance"),
                new RouteMeasure(new BigDecimal(distance), 10),
                null,
                null,
                doctors,
                confidence,
                confidence,
                List.of("hospital"),
                List.of());
    }

    private DoctorRecommendation doctor(
            String npi,
            String hospitalId,
            List<String> evidence,
            ConfidenceResult confidence) {
        return new DoctorRecommendation(
                npi,
                "Jane Doe",
                "MD",
                "Cardiology",
                List.of(),
                null,
                null,
                hospitalId,
                "Doctor recommendation paragraph.",
                List.of("specialty", "affiliation"),
                evidence,
                confidence);
    }
}
