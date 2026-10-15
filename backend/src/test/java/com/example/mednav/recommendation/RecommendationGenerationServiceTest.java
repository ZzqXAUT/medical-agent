package com.example.mednav.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mednav.clinical.ClinicalDecision;
import com.example.mednav.clinical.ClinicalReadiness;
import com.example.mednav.clinical.DiseaseHypothesis;
import com.example.mednav.clinical.DiseaseSeverity;
import com.example.mednav.clinical.SymptomCase;
import com.example.mednav.cms.CmsDoctorCandidate;
import com.example.mednav.cms.CmsHospital;
import com.example.mednav.cms.CmsProviderQueryService;
import com.example.mednav.cms.CmsSpecialtyFact;
import com.example.mednav.location.GeoPoint;
import com.example.mednav.location.LocationResult;
import com.example.mednav.location.LocationRoutingResult;
import com.example.mednav.location.LocationSource;
import com.example.mednav.route.HospitalRoute;
import com.example.mednav.route.RouteElementStatus;
import com.example.mednav.route.RouteMatrixResult;
import com.example.mednav.route.RouteMeasure;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecommendationGenerationServiceTest {

    private static final String SNAPSHOT = "11111111-1111-1111-1111-111111111111";
    private final CmsProviderQueryService providers = mock(CmsProviderQueryService.class);
    private final ConfidenceService confidence = new ConfidenceService();
    private final RecommendationGenerationService service =
            new RecommendationGenerationService(providers, confidence);
    private List<CmsHospital> hospitals;

    @BeforeEach
    void setUp() {
        hospitals = List.of(
                hospital("H1", 5),
                hospital("H2", 4),
                hospital("H3", null),
                hospital("H4", 5));
        when(providers.hospitalsBySnapshotAndIds(eq(SNAPSHOT), anyList()))
                .thenReturn(hospitals);
        when(providers.doctorsAtHospital(eq(SNAPSHOT), anyString(), anyList(), eq(2)))
                .thenReturn(List.of(doctor("1000000003"), doctor("1000000001"), doctor("1000000002")));
    }

    @Test
    void appliesPerDiseaseRangeHospitalOrderAndStableDoctorLimit() {
        NavigationRecommendationResult result = service.generate(request());

        assertThat(result.diseases()).extracting(item -> item.hypothesis().name())
                .containsExactly("较重候选", "中等候选", "较轻候选");
        assertThat(result.diseases().get(0).hospitals())
                .extracting(HospitalRecommendation::hospitalId)
                .containsExactly("H4", "H1");
        assertThat(result.diseases().get(1).hospitals())
                .extracting(HospitalRecommendation::hospitalId)
                .containsExactly("H4", "H2");
        assertThat(result.diseases().get(2).hospitals())
                .extracting(HospitalRecommendation::hospitalId)
                .containsExactly("H4", "H1");
        assertThat(result.diseases().get(0).hospitals().get(0).doctors())
                .extracting(DoctorRecommendation::npi)
                .containsExactly("1000000001", "1000000002");
        assertThat(result.evidence()).isNotEmpty();
        assertThat(result.overallConfidence().score()).isBetween(65, 100);
        assertThat(result.displayLanguage()).isEqualTo("zh-CN");
        assertThat(result.diseases().get(0).recommendationReason())
                .contains("不能替代医生面诊")
                .doesNotContain("SEVERE", "MILD", "CMS");
        assertThat(result.diseases().get(0).hospitals().get(0).recommendationReason())
                .contains("医院评分和驾车距离")
                .doesNotContain("overall rating", "miles", "Facility Affiliation");
        assertThat(result.diseases().get(0).hospitals().get(0).doctors().get(0)
                        .recommendationReason())
                .contains("不涉及医生能力排名")
                .doesNotContain("DAC", "Facility ID", "CARDIOVASCULAR DISEASE");
    }

    @Test
    void keepsHospitalWithExplicitLimitationWhenCmsHasNoMatchingDoctor() {
        when(providers.doctorsAtHospital(eq(SNAPSHOT), anyString(), anyList(), eq(2)))
                .thenReturn(List.of());

        NavigationRecommendationResult result = service.generate(request());

        HospitalRecommendation hospital = result.diseases().get(0).hospitals().get(0);
        assertThat(hospital.doctors()).isEmpty();
        assertThat(hospital.limitations()).singleElement()
                .asString()
                .contains("未找到");
    }

    @Test
    void generatesEnglishPatientFacingReasonsForAnEnglishRun() {
        NavigationRecommendationResult result = service.generate(request("en-US"));

        assertThat(result.displayLanguage()).isEqualTo("en-US");
        assertThat(result.diseases().get(0).hypothesis().name()).isEqualTo("Severe possibility");
        assertThat(result.diseases().get(0).recommendationReason())
                .contains("does not replace an in-person medical evaluation")
                .doesNotContain("较重", "医院综合评分");
        assertThat(result.diseases().get(0).hospitals().get(0).recommendationReason())
                .contains("hospital rating and driving distance")
                .doesNotContain("英里", "公开资料");
    }

    private RecommendationRequest request() {
        return request("zh-CN");
    }

    private RecommendationRequest request(String displayLanguage) {
        boolean chinese = "zh-CN".equals(displayLanguage);
        List<DiseaseHypothesis> hypotheses = List.of(
                hypothesis(chinese ? "较轻候选" : "Mild possibility", DiseaseSeverity.MILD, 0.70, chinese),
                hypothesis(chinese ? "较重候选" : "Severe possibility", DiseaseSeverity.SEVERE, 0.90, chinese),
                hypothesis(chinese ? "中等候选" : "Moderate possibility", DiseaseSeverity.MODERATE, 0.80, chinese));
        SymptomCase symptoms = new SymptomCase(
                chinese ? "胸痛两小时" : "chest pain for two hours",
                "chest pain",
                chinese ? "两小时" : "two hours",
                8,
                List.of(),
                List.of("chest pain"),
                chinese
                        ? List.of("主要症状：胸痛", "持续时间：两小时")
                        : List.of("Main symptom: chest pain", "Duration: two hours"));
        ClinicalDecision clinical = new ClinicalDecision(
                ClinicalReadiness.READY,
                List.of(),
                null,
                0.95,
                symptoms,
                hypotheses);
        RouteMatrixResult matrix = new RouteMatrixResult(
                SNAPSHOT,
                4,
                12,
                3,
                0,
                12,
                List.of(
                        route("H1", "9.0"),
                        route("H2", "2.0"),
                        route("H3", "1.0"),
                        route("H4", "3.0")));
        LocationResult location = new LocationResult(
                new GeoPoint(31.2, -85.3),
                "Dothan, AL",
                "Dothan",
                "AL",
                "36301",
                "US",
                "test",
                LocationSource.MANUAL,
                Instant.now());
        UUID conversationId = UUID.randomUUID();
        return new RecommendationRequest(
                conversationId,
                UUID.randomUUID(),
                3,
                1,
                1,
                clinical,
                new LocationRoutingResult(location, matrix),
                displayLanguage);
    }

    private DiseaseHypothesis hypothesis(
            String name, DiseaseSeverity severity, double probability, boolean chinese) {
        return new DiseaseHypothesis(
                name,
                severity,
                probability,
                List.of(chinese ? "主要症状：胸痛" : "Main symptom: chest pain"),
                List.of(),
                List.of("CARDIOVASCULAR DISEASE"));
    }

    private CmsHospital hospital(String id, Integer rating) {
        return new CmsHospital(
                SNAPSHOT,
                id,
                "Hospital " + id,
                id + " Main St",
                "Dothan",
                "AL",
                "36301",
                "Houston",
                "3345550000",
                "Acute Care Hospitals",
                "Nonprofit",
                true,
                false,
                rating,
                null);
    }

    private HospitalRoute route(String hospitalId, String distance) {
        RouteMeasure drive = new RouteMeasure(new BigDecimal(distance), 15);
        return new HospitalRoute(
                SNAPSHOT,
                hospitalId,
                drive,
                new RouteMeasure(new BigDecimal(distance), 25),
                new RouteMeasure(new BigDecimal(distance), 90),
                Instant.now(),
                RouteElementStatus.SUCCESS);
    }

    private CmsDoctorCandidate doctor(String npi) {
        return new CmsDoctorCandidate(
                SNAPSHOT,
                "H4",
                npi,
                "JANE",
                null,
                "DOE",
                null,
                "MD",
                "TEST SCHOOL",
                2000,
                "Hospital",
                List.of(new CmsSpecialtyFact(
                        "PRIMARY", "CARDIOVASCULAR DISEASE", "CARDIOVASCULAR DISEASE")));
    }
}
