package com.example.mednav.recommendation;

import com.example.mednav.clinical.DiseaseHypothesis;
import com.example.mednav.clinical.DiseaseSeverity;
import com.example.mednav.cms.CmsDoctorCandidate;
import com.example.mednav.cms.CmsHospital;
import com.example.mednav.cms.CmsProviderQueryService;
import com.example.mednav.cms.CmsSpecialtyFact;
import com.example.mednav.i18n.DisplayLanguage;
import com.example.mednav.route.HospitalRoute;
import com.example.mednav.route.RouteElementStatus;
import com.example.mednav.route.RouteMatrixResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RecommendationGenerationService {

    private static final Logger log =
            LoggerFactory.getLogger(RecommendationGenerationService.class);
    private static final BigDecimal MODERATE_LIMIT_MILES = new BigDecimal("5.0");
    private static final BigDecimal MILD_LIMIT_MILES = new BigDecimal("10.0");

    private final CmsProviderQueryService providerQueryService;
    private final ConfidenceService confidenceService;

    public RecommendationGenerationService(
            CmsProviderQueryService providerQueryService,
            ConfidenceService confidenceService) {
        this.providerQueryService = providerQueryService;
        this.confidenceService = confidenceService;
    }

    public NavigationRecommendationResult generate(RecommendationRequest request) {
        RouteMatrixResult matrix = request.locationRoutingResult().routeMatrix();
        String snapshotId = matrix.snapshotId();
        Map<String, HospitalRoute> routes = new LinkedHashMap<>();
        for (HospitalRoute route : matrix.routes()) {
            if (snapshotId.equals(route.snapshotId())) {
                routes.putIfAbsent(route.hospitalId(), route);
            }
        }
        Map<String, CmsHospital> hospitals = new LinkedHashMap<>();
        providerQueryService.hospitalsBySnapshotAndIds(snapshotId, List.copyOf(routes.keySet()))
                .forEach(hospital -> hospitals.put(hospital.facilityId(), hospital));

        EvidenceRegistry evidence = new EvidenceRegistry();
        List<DiseaseHypothesis> orderedHypotheses = request.clinicalDecision().hypotheses().stream()
                .sorted(Comparator.comparingDouble(DiseaseHypothesis::estimatedProbability)
                        .reversed()
                        .thenComparing(DiseaseHypothesis::name))
                .limit(3)
                .toList();
        List<DiseaseRecommendation> diseaseResults = new ArrayList<>();
        for (int index = 0; index < orderedHypotheses.size(); index++) {
            DiseaseHypothesis hypothesis = orderedHypotheses.get(index);
            int diseaseRank = index + 1;
            diseaseResults.add(buildDisease(
                    request,
                    hypothesis,
                    diseaseRank,
                    snapshotId,
                    routes,
                    hospitals,
                    evidence));
        }
        ConfidenceResult overall = confidenceService.overall(diseaseResults);
        log.info(
                "recommendation_generated conversationId={} locationRevision={} clinicalRevision={} snapshotId={} diseases={} hospitals={} doctors={} confidence={}",
                request.conversationId(),
                request.locationRevision(),
                request.clinicalRevision(),
                snapshotId,
                diseaseResults.size(),
                diseaseResults.stream().mapToInt(item -> item.hospitals().size()).sum(),
                diseaseResults.stream()
                        .flatMap(item -> item.hospitals().stream())
                        .mapToInt(item -> item.doctors().size())
                        .sum(),
                overall.score());
        return new NavigationRecommendationResult(
                request.conversationId(),
                request.locationTaskId(),
                request.contextVersion(),
                request.locationRevision(),
                request.clinicalRevision(),
                snapshotId,
                request.displayLanguage(),
                RecommendationStatus.READY,
                overall,
                0,
                false,
                List.of(),
                diseaseResults,
                evidence.values(),
                Instant.now());
    }

    private DiseaseRecommendation buildDisease(
            RecommendationRequest request,
            DiseaseHypothesis hypothesis,
            int diseaseRank,
            String snapshotId,
            Map<String, HospitalRoute> routes,
            Map<String, CmsHospital> hospitals,
            EvidenceRegistry evidence) {
        List<String> diseaseEvidence = new ArrayList<>();
        for (int index = 0; index < hypothesis.supportingFacts().size(); index++) {
            String evidenceId = "clinical:%d:disease:%d:fact:%d"
                    .formatted(request.clinicalRevision(), diseaseRank, index + 1);
            diseaseEvidence.add(evidenceId);
            evidence.add(new EvidenceItem(
                    evidenceId,
                    "USER_REPORTED",
                    null,
                    request.conversationId().toString(),
                    hypothesis.supportingFacts().get(index)));
        }

        List<HospitalRecommendation> candidates = new ArrayList<>();
        for (HospitalRoute route : routes.values()) {
            CmsHospital hospital = hospitals.get(route.hospitalId());
            if (hospital == null || !withinRange(hypothesis.severity(), route)) {
                continue;
            }
            candidates.add(buildHospital(
                    hypothesis,
                    snapshotId,
                    hospital,
                    route,
                    evidence,
                    request.displayLanguage()));
        }
        candidates.sort(hospitalComparator());
        List<HospitalRecommendation> selected = new ArrayList<>();
        for (int index = 0; index < Math.min(2, candidates.size()); index++) {
            selected.add(candidates.get(index).withRank(index + 1));
        }
        List<String> limitations = selected.isEmpty()
                ? List.of(rangeEmptyMessage(hypothesis.severity(), request.displayLanguage()))
                : List.of();
        PublicDiseaseHypothesis publicHypothesis = new PublicDiseaseHypothesis(
                hypothesis.name(),
                hypothesis.severity(),
                hypothesis.estimatedProbability(),
                hypothesis.supportingFacts(),
                hypothesis.limitations());
        return new DiseaseRecommendation(
                publicHypothesis,
                diseaseRank,
                diseaseReason(hypothesis, request.displayLanguage()),
                confidenceService.disease(
                        hypothesis, request.clinicalDecision().symptomCase()),
                selected,
                diseaseEvidence,
                limitations);
    }

    private HospitalRecommendation buildHospital(
            DiseaseHypothesis hypothesis,
            String snapshotId,
            CmsHospital hospital,
            HospitalRoute route,
            EvidenceRegistry evidence,
            String displayLanguage) {
        boolean chinese = DisplayLanguage.isChinese(displayLanguage);
        String hospitalEvidenceId =
                "cms:%s:hospital:%s".formatted(snapshotId, hospital.facilityId());
        evidence.add(new EvidenceItem(
                hospitalEvidenceId,
                "CMS_HOSPITAL_GENERAL_INFORMATION",
                snapshotId,
                hospital.facilityId(),
                hospital.facilityName() + "；CMS overall rating="
                        + Objects.toString(hospital.overallRating(), "unavailable")));
        List<String> evidenceIds = new ArrayList<>(List.of(hospitalEvidenceId));
        List<String> reasons = new ArrayList<>();
        reasons.add(hospital.overallRating() == null
                ? (chinese
                        ? "公开医院资料暂未提供该院的综合评分。"
                        : "The public hospital record does not currently include an overall rating.")
                : (chinese
                        ? "医院综合评分为 " + hospital.overallRating() + "/5。"
                        : "The hospital has an overall rating of " + hospital.overallRating() + "/5."));
        if (route.drive() != null) {
            String routeEvidenceId =
                    "route:%s:drive:%s".formatted(snapshotId, hospital.facilityId());
            evidenceIds.add(routeEvidenceId);
            evidence.add(new EvidenceItem(
                    routeEvidenceId,
                    "GOOGLE_ROUTE_MATRIX",
                    snapshotId,
                    hospital.facilityId(),
                    "DRIVE distance=" + route.drive().distanceMiles()
                            + " miles; duration=" + route.drive().durationMinutes() + " minutes"));
            reasons.add(chinese
                    ? "预计驾车 " + route.drive().distanceMiles() + " 英里，约 "
                            + route.drive().durationMinutes() + " 分钟。"
                    : "The estimated drive is " + route.drive().distanceMiles() + " miles and about "
                            + route.drive().durationMinutes() + " minutes.");
        } else {
            reasons.add(chinese
                    ? "该医院位于当前城市，但暂时无法取得驾车路线，因此距离未参与排序。"
                    : "This hospital is in the current city, but a driving route was unavailable, so distance was not used in ranking.");
        }

        List<CmsDoctorCandidate> candidates = providerQueryService.doctorsAtHospital(
                snapshotId, hospital.facilityId(), hypothesis.targetSpecialties(), 2);
        List<DoctorRecommendation> doctors = candidates.stream()
                .filter(candidate -> snapshotId.equals(candidate.snapshotId()))
                .filter(candidate -> hospital.facilityId().equals(candidate.facilityId()))
                .sorted(Comparator.comparing(CmsDoctorCandidate::npi))
                .limit(2)
                .map(candidate -> buildDoctor(
                        snapshotId, hospital, candidate, evidence, displayLanguage))
                .toList();
        List<String> limitations = new ArrayList<>();
        if (doctors.isEmpty()) {
            limitations.add(chinese
                    ? "当前公开资料中暂未找到专业方向匹配且在该院执业的医生。"
                    : "The current public records did not identify a doctor whose specialty matches and who practices at this hospital.");
        } else {
            reasons.add(chinese
                    ? "找到 " + doctors.size() + " 名专业方向匹配且在该院执业的医生。"
                    : doctors.size() + " doctor(s) have a matching specialty and a verified affiliation with this hospital.");
        }
        ConfidenceResult hospitalConfidence =
                confidenceService.hospital(hospital, route, !doctors.isEmpty());
        return new HospitalRecommendation(
                hospital.facilityId(),
                hospital.facilityName(),
                0,
                hospital.overallRating(),
                formatAddress(hospital),
                hospitalReason(hospital, route, doctors.size(), displayLanguage),
                reasons,
                route.drive(),
                route.transit(),
                route.walk(),
                doctors,
                hospitalConfidence,
                confidenceService.explanation(reasons, evidenceIds),
                evidenceIds,
                limitations);
    }

    private DoctorRecommendation buildDoctor(
            String snapshotId,
            CmsHospital hospital,
            CmsDoctorCandidate candidate,
            EvidenceRegistry evidence,
            String displayLanguage) {
        boolean chinese = DisplayLanguage.isChinese(displayLanguage);
        String doctorEvidenceId = "cms:%s:doctor:%s".formatted(snapshotId, candidate.npi());
        String affiliationEvidenceId = "cms:%s:affiliation:%s:%s"
                .formatted(snapshotId, candidate.npi(), hospital.facilityId());
        String specialties = candidate.matchedSpecialties().stream()
                .map(CmsSpecialtyFact::name)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("unknown");
        evidence.add(new EvidenceItem(
                doctorEvidenceId,
                "CMS_DAC_NATIONAL_DOWNLOADABLE_FILE",
                snapshotId,
                candidate.npi(),
                "NPI=" + candidate.npi() + "; matched specialties=" + specialties));
        evidence.add(new EvidenceItem(
                affiliationEvidenceId,
                "CMS_FACILITY_AFFILIATION",
                snapshotId,
                candidate.npi(),
                "NPI=" + candidate.npi() + " is affiliated with Facility ID="
                        + hospital.facilityId()));
        String primary = candidate.matchedSpecialties().stream()
                .filter(item -> "PRIMARY".equalsIgnoreCase(item.type()))
                .map(CmsSpecialtyFact::name)
                .findFirst()
                .orElse(null);
        List<String> secondary = candidate.matchedSpecialties().stream()
                .filter(item -> !"PRIMARY".equalsIgnoreCase(item.type()))
                .map(CmsSpecialtyFact::name)
                .distinct()
                .toList();
        String visibleSpecialties = visibleSpecialties(candidate, displayLanguage);
        List<String> reasons = List.of(
                chinese
                        ? "公开执业资料显示，该医生的专业方向与本次就诊需求相符。"
                        : "Public professional records show that this doctor's specialty matches the current care need.",
                chinese
                        ? "公开任职资料显示，该医生目前在这家医院执业。"
                        : "Public affiliation records show that this doctor practices at this hospital.");
        return new DoctorRecommendation(
                candidate.npi(),
                displayName(candidate),
                candidate.credential(),
                primary,
                secondary,
                candidate.medicalSchool(),
                candidate.graduationYear(),
                hospital.facilityId(),
                doctorReason(visibleSpecialties, displayLanguage),
                reasons,
                List.of(doctorEvidenceId, affiliationEvidenceId),
                confidenceService.doctor(candidate));
    }

    private String diseaseReason(DiseaseHypothesis hypothesis, String displayLanguage) {
        boolean chinese = DisplayLanguage.isChinese(displayLanguage);
        String facts = String.join(chinese ? "、" : ", ", hypothesis.supportingFacts());
        int probability = (int) Math.round(hypothesis.estimatedProbability() * 100);
        if (chinese) {
            String severity = switch (hypothesis.severity()) {
                case SEVERE -> "较重";
                case MODERATE -> "中等";
                case MILD -> "较轻";
            };
            return "你提到的情况包括" + facts + "。结合目前的信息，“" + hypothesis.name()
                    + "”是值得进一步排查的方向之一，在本次候选中的相对可能性约为 "
                    + probability + "%；症状程度暂评为" + severity
                    + "。这项结果用于帮助选择后续就诊资源，不能替代医生面诊和必要检查。";
        }
        return "You reported " + facts + ". Based on the information currently available, "
                + hypothesis.name() + " is one possibility worth evaluating further, with an estimated "
                + probability + "% likelihood within this candidate set. The preliminary severity is "
                + hypothesis.severity().name().toLowerCase(Locale.ROOT)
                + ". This result helps select follow-up care resources and does not replace an in-person medical evaluation.";
    }

    private String hospitalReason(
            CmsHospital hospital,
            HospitalRoute route,
            int doctorCount,
            String displayLanguage) {
        boolean chinese = DisplayLanguage.isChinese(displayLanguage);
        if (chinese) {
            String rating = hospital.overallRating() == null
                    ? "公开资料暂未提供综合评分"
                    : "医院综合评分为 " + hospital.overallRating() + "/5";
            String travel = route.drive() == null
                    ? "暂时无法取得驾车路线"
                    : "预计驾车 " + route.drive().distanceMiles() + " 英里，约 "
                            + route.drive().durationMinutes() + " 分钟";
            String provider = doctorCount == 0
                    ? "暂未找到专业方向匹配且在该院执业的医生"
                    : "另有 " + doctorCount + " 名专业方向匹配且在该院执业的医生可供参考";
            return "这家医院进入推荐，主要是因为其" + rating + "，从你当前位置出发"
                    + travel + "；" + provider
                    + "。排序综合考虑医院评分和驾车距离，仅用于帮助你比较就诊选择，不代表治疗效果。";
        }
        String rating = hospital.overallRating() == null
                ? "does not currently have a public overall rating"
                : "has an overall rating of " + hospital.overallRating() + "/5";
        String travel = route.drive() == null
                ? "a driving route is currently unavailable"
                : "the estimated drive is " + route.drive().distanceMiles() + " miles and about "
                        + route.drive().durationMinutes() + " minutes";
        String provider = doctorCount == 0
                ? "No matching affiliated doctor was found in the current public records"
                : doctorCount + " matching affiliated doctor(s) are also available for consideration";
        return "This hospital is included because it " + rating + ", and " + travel + ". "
                + provider + ". The order combines hospital rating and driving distance to help compare care options; it does not predict treatment outcomes.";
    }

    private String doctorReason(String specialties, String displayLanguage) {
        if (DisplayLanguage.isChinese(displayLanguage)) {
            return "这位医生的公开执业资料显示，其专业方向为" + specialties
                    + "，与本次就诊需求相符，并且目前在这家医院执业。推荐依据是专业匹配和任职关系，不涉及医生能力排名，也不代表对诊疗结果的保证。";
        }
        return "Public professional records list this doctor's specialty as " + specialties
                + ", which matches the current care need, and confirm that the doctor practices at this hospital. The recommendation is based on specialty fit and hospital affiliation, not a quality ranking or a guarantee of outcome.";
    }

    private String visibleSpecialties(
            CmsDoctorCandidate candidate, String displayLanguage) {
        List<String> names = candidate.matchedSpecialties().stream()
                .map(CmsSpecialtyFact::name)
                .distinct()
                .toList();
        if (!DisplayLanguage.isChinese(displayLanguage)) {
            return String.join(", ", names);
        }
        List<String> localized = names.stream()
                .map(this::chineseSpecialty)
                .distinct()
                .toList();
        return localized.isEmpty() ? "与当前就诊方向相关的专业" : String.join("、", localized);
    }

    private String chineseSpecialty(String specialty) {
        if (specialty == null) return "与当前就诊方向相关的专业";
        return switch (specialty.strip().toUpperCase(Locale.ROOT)) {
            case "CARDIOVASCULAR DISEASE", "CARDIOLOGY" -> "心血管疾病相关专业";
            case "GASTROENTEROLOGY" -> "消化系统疾病相关专业";
            case "INTERNAL MEDICINE" -> "内科相关专业";
            case "FAMILY PRACTICE", "FAMILY MEDICINE" -> "家庭医学相关专业";
            case "PULMONARY DISEASE" -> "呼吸系统疾病相关专业";
            case "GENERAL SURGERY" -> "普通外科相关专业";
            case "NEUROLOGY" -> "神经系统疾病相关专业";
            default -> "与当前就诊方向相关的专业";
        };
    }

    private boolean withinRange(DiseaseSeverity severity, HospitalRoute route) {
        if (severity == DiseaseSeverity.SEVERE) {
            return true;
        }
        if (route.status() != RouteElementStatus.SUCCESS || route.drive() == null) {
            return false;
        }
        BigDecimal limit = severity == DiseaseSeverity.MODERATE
                ? MODERATE_LIMIT_MILES
                : MILD_LIMIT_MILES;
        return route.drive().distanceMiles().compareTo(limit) <= 0;
    }

    private Comparator<HospitalRecommendation> hospitalComparator() {
        Comparator<Integer> rating = Comparator.nullsLast(Comparator.reverseOrder());
        Comparator<BigDecimal> distance = Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(HospitalRecommendation::cmsRating, rating)
                .thenComparing(
                        item -> item.drive() == null ? null : item.drive().distanceMiles(),
                        distance)
                .thenComparing(HospitalRecommendation::hospitalId);
    }

    private String rangeEmptyMessage(DiseaseSeverity severity, String displayLanguage) {
        if (DisplayLanguage.isChinese(displayLanguage)) {
            return switch (severity) {
                case SEVERE -> "当前城市内暂未找到符合条件的医院。";
                case MODERATE -> "驾车 5 英里范围内暂未找到符合条件的医院。";
                case MILD -> "驾车 10 英里范围内暂未找到符合条件的医院。";
            };
        }
        return switch (severity) {
            case SEVERE -> "No eligible hospital was found in the current city.";
            case MODERATE -> "No eligible hospital was found within a 5-mile drive.";
            case MILD -> "No eligible hospital was found within a 10-mile drive.";
        };
    }

    private String formatAddress(CmsHospital hospital) {
        return String.join(", ", List.of(
                hospital.address(),
                hospital.city(),
                hospital.state() + " " + hospital.zipCode()));
    }

    private String displayName(CmsDoctorCandidate doctor) {
        List<String> names = new ArrayList<>();
        names.add(doctor.firstName());
        if (doctor.middleName() != null && !doctor.middleName().isBlank()) {
            names.add(doctor.middleName());
        }
        names.add(doctor.lastName());
        if (doctor.suffix() != null && !doctor.suffix().isBlank()) {
            names.add(doctor.suffix());
        }
        String name = String.join(" ", names).replaceAll("\\s+", " ").strip();
        return name.toUpperCase(Locale.ROOT).equals(name) ? titleCase(name) : name;
    }

    private String titleCase(String value) {
        StringBuilder result = new StringBuilder();
        for (String part : value.toLowerCase(Locale.ROOT).split(" ")) {
            if (!part.isBlank()) {
                if (!result.isEmpty()) result.append(' ');
                result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return result.toString();
    }

    private static final class EvidenceRegistry {
        private final Map<String, EvidenceItem> items = new LinkedHashMap<>();

        private void add(EvidenceItem item) {
            items.putIfAbsent(item.evidenceId(), item);
        }

        private List<EvidenceItem> values() {
            return List.copyOf(items.values());
        }
    }
}
