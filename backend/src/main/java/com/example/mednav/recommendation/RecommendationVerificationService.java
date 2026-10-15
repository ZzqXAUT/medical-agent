package com.example.mednav.recommendation;

import com.example.mednav.clinical.DiseaseSeverity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class RecommendationVerificationService {

    private static final Logger log =
            LoggerFactory.getLogger(RecommendationVerificationService.class);

    private final RecommendationGate gate;
    private final SemanticVerificationClient semanticClient;
    private final ConfidenceService confidenceService;
    private final boolean semanticEnabled;
    private final int semanticThreshold;
    private final int maxRounds;

    public RecommendationVerificationService(
            RecommendationGate gate,
            SemanticVerificationClient semanticClient,
            ConfidenceService confidenceService,
            @Value("${mednav.recommendation.semantic-verification-enabled:true}")
                    boolean semanticEnabled,
            @Value("${mednav.recommendation.semantic-threshold:65}") int semanticThreshold,
            @Value("${mednav.recommendation.max-verification-rounds:3}") int maxRounds) {
        this.gate = gate;
        this.semanticClient = semanticClient;
        this.confidenceService = confidenceService;
        this.semanticEnabled = semanticEnabled;
        this.semanticThreshold = semanticThreshold;
        this.maxRounds = Math.min(3, Math.max(1, maxRounds));
    }

    public Mono<NavigationRecommendationResult> verify(
            NavigationRecommendationResult generated) {
        NavigationRecommendationResult current = generated;
        List<VerificationFinding> hardFindings = gate.validate(current);
        int rounds = 0;
        while (!hardFindings.isEmpty() && rounds < maxRounds) {
            rounds++;
            log.info(
                    "recommendation_local_repair conversationId={} round={} findings={} repairScope=FAILED_ITEMS_ONLY",
                    generated.conversationId(),
                    rounds,
                    hardFindings.size());
            current = sanitize(current);
            hardFindings = gate.validate(current);
        }
        if (!hardFindings.isEmpty()) {
            current = dropAllUnverified(current, rounds, hardFindings);
        }
        NavigationRecommendationResult deterministic = withVerification(
                current,
                hardFindings.isEmpty() ? RecommendationStatus.READY : RecommendationStatus.READY_WITH_WARNINGS,
                rounds,
                false,
                hardFindings);
        int verifiedRounds = rounds;
        boolean semanticRisk = semanticEnabled && hasSemanticRisk(deterministic);
        if (!semanticRisk) {
            log.info(
                    "semantic_verification_skipped conversationId={} reason={}",
                    generated.conversationId(),
                    semanticEnabled ? "NO_SEMANTIC_RISK" : "DISABLED");
            return Mono.just(deterministic);
        }
        log.info(
                "semantic_verification_triggered conversationId={} confidence={} threshold={}",
                generated.conversationId(),
                deterministic.overallConfidence().score(),
                semanticThreshold);
        return semanticClient.verify(deterministic)
                .map(report -> withVerification(
                        deterministic,
                        report.findings().isEmpty()
                                ? deterministic.status()
                                : RecommendationStatus.READY_WITH_WARNINGS,
                        verifiedRounds,
                        true,
                        report.findings()))
                .onErrorResume(error -> {
                    log.warn(
                            "semantic_verification_failed conversationId={} errorType={}",
                            generated.conversationId(),
                            error.getClass().getSimpleName());
                    VerificationFinding unavailable = new VerificationFinding(
                            "$",
                            "SEMANTIC_VERIFICATION_UNAVAILABLE",
                            VerificationSeverity.WARNING,
                            "Optional semantic verification was unavailable; deterministic gates passed.",
                            "NONE",
                            true);
                    return Mono.just(withVerification(
                            deterministic,
                            RecommendationStatus.READY_WITH_WARNINGS,
                            verifiedRounds,
                            true,
                            List.of(unavailable)));
                });
    }

    private NavigationRecommendationResult sanitize(NavigationRecommendationResult result) {
        Set<String> evidence = new HashSet<>();
        result.evidence().forEach(item -> evidence.add(item.evidenceId()));
        List<DiseaseRecommendation> diseases = result.diseases().stream()
                .filter(item -> !item.hypothesis().supportingFacts().isEmpty()
                        && evidence.containsAll(item.evidenceIds())
                        && !item.evidenceIds().isEmpty())
                .sorted(Comparator.comparingDouble(
                                (DiseaseRecommendation item) ->
                                        item.hypothesis().estimatedProbability())
                        .reversed()
                        .thenComparing(item -> item.hypothesis().name()))
                .distinct()
                .limit(3)
                .toList();
        List<DiseaseRecommendation> repairedDiseases = new ArrayList<>();
        for (int diseaseIndex = 0; diseaseIndex < diseases.size(); diseaseIndex++) {
            DiseaseRecommendation disease = diseases.get(diseaseIndex);
            List<HospitalRecommendation> hospitals = repairHospitals(disease, evidence);
            repairedDiseases.add(disease.withRankAndHospitals(diseaseIndex + 1, hospitals));
        }
        return copy(result, repairedDiseases, result.status(), result.verificationRounds(),
                result.semanticVerificationTriggered(), result.findings());
    }

    private List<HospitalRecommendation> repairHospitals(
            DiseaseRecommendation disease, Set<String> evidence) {
        Comparator<HospitalRecommendation> comparator = hospitalComparator();
        List<HospitalRecommendation> eligible = disease.hospitals().stream()
                .filter(item -> item.hospitalId() != null
                        && withinRange(disease.hypothesis().severity(), item)
                        && !item.evidenceIds().isEmpty()
                        && evidence.containsAll(item.evidenceIds()))
                .sorted(comparator)
                .toList();
        List<HospitalRecommendation> result = new ArrayList<>();
        Set<String> hospitalIds = new HashSet<>();
        for (HospitalRecommendation hospital : eligible) {
            if (!hospitalIds.add(hospital.hospitalId()) || result.size() == 2) continue;
            List<DoctorRecommendation> doctors = hospital.doctors().stream()
                    .filter(item -> validDoctor(item, hospital.hospitalId(), evidence))
                    .sorted(Comparator.comparing(DoctorRecommendation::npi))
                    .filter(new java.util.function.Predicate<>() {
                        private final Set<String> npis = new HashSet<>();

                        @Override
                        public boolean test(DoctorRecommendation item) {
                            return npis.add(item.npi());
                        }
                    })
                    .limit(2)
                    .toList();
            result.add(new HospitalRecommendation(
                    hospital.hospitalId(),
                    hospital.name(),
                    result.size() + 1,
                    hospital.cmsRating(),
                    hospital.address(),
                    hospital.recommendationReason(),
                    hospital.diseaseHospitalMatchReasons(),
                    hospital.drive(),
                    hospital.transit(),
                    hospital.walk(),
                    doctors,
                    hospital.hospitalConfidence(),
                    hospital.explanationConfidence(),
                    hospital.evidenceIds(),
                    hospital.limitations()));
        }
        return List.copyOf(result);
    }

    private boolean validDoctor(
            DoctorRecommendation doctor, String hospitalId, Set<String> evidence) {
        return doctor.npi() != null
                && doctor.npi().matches("\\d{10}")
                && hospitalId.equals(doctor.affiliationFacilityId())
                && doctor.recommendationReason() != null
                && !doctor.recommendationReason().isBlank()
                && !doctor.recommendationReasons().isEmpty()
                && !doctor.evidenceIds().isEmpty()
                && evidence.containsAll(doctor.evidenceIds());
    }

    private boolean withinRange(DiseaseSeverity severity, HospitalRecommendation hospital) {
        if (severity == DiseaseSeverity.SEVERE) return true;
        if (hospital.drive() == null) return false;
        BigDecimal limit = severity == DiseaseSeverity.MODERATE
                ? new BigDecimal("5.0")
                : new BigDecimal("10.0");
        return hospital.drive().distanceMiles().compareTo(limit) <= 0;
    }

    private Comparator<HospitalRecommendation> hospitalComparator() {
        Comparator<Integer> ratings = Comparator.nullsLast(Comparator.reverseOrder());
        Comparator<BigDecimal> distances = Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(HospitalRecommendation::cmsRating, ratings)
                .thenComparing(
                        item -> item.drive() == null ? null : item.drive().distanceMiles(),
                        distances)
                .thenComparing(HospitalRecommendation::hospitalId);
    }

    private NavigationRecommendationResult dropAllUnverified(
            NavigationRecommendationResult result,
            int rounds,
            List<VerificationFinding> findings) {
        return new NavigationRecommendationResult(
                result.conversationId(),
                result.locationTaskId(),
                result.contextVersion(),
                result.locationRevision(),
                result.clinicalRevision(),
                result.cmsSnapshotId(),
                result.displayLanguage(),
                RecommendationStatus.READY_WITH_WARNINGS,
                confidenceService.overall(List.of()),
                rounds,
                false,
                findings,
                List.of(),
                result.evidence(),
                result.generatedAt());
    }

    private boolean hasSemanticRisk(NavigationRecommendationResult result) {
        boolean hasHospitals = result.diseases().stream()
                .anyMatch(item -> !item.hospitals().isEmpty());
        if (hasHospitals && result.overallConfidence().score() < semanticThreshold) return true;
        return result.diseases().stream()
                .flatMap(item -> item.hospitals().stream())
                .anyMatch(item -> item.explanationConfidence().score() < semanticThreshold);
    }

    private NavigationRecommendationResult withVerification(
            NavigationRecommendationResult result,
            RecommendationStatus status,
            int rounds,
            boolean semanticTriggered,
            List<VerificationFinding> findings) {
        return copy(
                result,
                result.diseases(),
                status,
                rounds,
                semanticTriggered,
                findings);
    }

    private NavigationRecommendationResult copy(
            NavigationRecommendationResult result,
            List<DiseaseRecommendation> diseases,
            RecommendationStatus status,
            int rounds,
            boolean semanticTriggered,
            List<VerificationFinding> findings) {
        return new NavigationRecommendationResult(
                result.conversationId(),
                result.locationTaskId(),
                result.contextVersion(),
                result.locationRevision(),
                result.clinicalRevision(),
                result.cmsSnapshotId(),
                result.displayLanguage(),
                status,
                confidenceService.overall(diseases),
                rounds,
                semanticTriggered,
                findings,
                diseases,
                result.evidence(),
                result.generatedAt());
    }
}
