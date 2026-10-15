package com.example.mednav.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class CmsSnapshotImportIntegrationTest {

    @Autowired
    private CmsSnapshotImportService importService;

    @Autowired
    private CmsProviderQueryService queryService;

    @Autowired
    private CmsSnapshotMapper snapshotMapper;

    @Test
    void importsAndJoinsACompleteSnapshotThenRejectsSchemaDriftAtomically() throws Exception {
        Path fixture = Path.of(getClass().getResource("/cms-fixture").toURI());
        CmsImportResult imported = importService.importSnapshot(files(fixture, null));

        assertThat(imported.status()).isEqualTo("ACTIVE");
        assertThat(imported.hospitals()).isEqualTo(2);
        assertThat(imported.doctors()).isEqualTo(2);
        assertThat(imported.affiliations()).isEqualTo(2);
        assertThat(imported.sourceRows()).containsEntry("doctor_national", 3L);

        List<CmsHospital> hospitals = queryService.hospitalsByCity("al", "dothan");
        assertThat(hospitals).singleElement().satisfies(hospital -> {
            assertThat(hospital.facilityId()).isEqualTo("010001");
            assertThat(hospital.overallRating()).isEqualTo(4);
        });
        List<CmsDoctorCandidate> doctors = queryService.doctorsAtHospital(
                "010001", List.of("Cardiovascular   Disease", "Internal Medicine"));
        assertThat(doctors).singleElement().satisfies(doctor -> {
            assertThat(doctor.npi()).isEqualTo("1003000126");
            assertThat(doctor.facilityId()).isEqualTo("010001");
            assertThat(doctor.matchedSpecialties())
                    .extracting(CmsSpecialtyFact::normalizedName)
                    .containsExactly("CARDIOVASCULAR DISEASE", "INTERNAL MEDICINE");
        });
        assertThat(queryService.hospitalsByCity("AL", "BOAZ"))
                .extracting(CmsHospital::facilityId)
                .containsExactly("01014F");
        assertThat(queryService.activeSpecialtyVocabulary())
                .contains("CARDIOVASCULAR DISEASE", "NEUROLOGY");

        assertThatThrownBy(() -> importService.importSnapshot(files(fixture, null).subList(0, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all three datasets");
        assertThat(snapshotMapper.findActiveSnapshot().snapshotId()).isEqualTo(imported.snapshotId());

        Path badAffiliation =
                Path.of(getClass().getResource("/cms-fixture-bad/Facility_Affiliation.csv").toURI());
        assertThatThrownBy(() -> importService.importSnapshot(files(fixture, badAffiliation)))
                .isInstanceOf(CmsImportException.class)
                .extracting(error -> ((CmsImportException) error).code())
                .isEqualTo("CMS_SCHEMA_DRIFT");

        assertThat(snapshotMapper.findActiveSnapshot().snapshotId()).isEqualTo(imported.snapshotId());
        assertThat(queryService.hospitalsByCity("AL", "DOTHAN"))
                .extracting(CmsHospital::facilityId)
                .containsExactly("010001");
    }

    @Test
    void refreshLeasePreventsASecondOwnerUntilReleased() {
        Instant now = Instant.now();
        assertThat(snapshotMapper.tryAcquireLock("owner-a", now, now.plus(Duration.ofMinutes(5))))
                .isEqualTo(1);
        assertThat(snapshotMapper.tryAcquireLock("owner-b", now, now.plus(Duration.ofMinutes(5))))
                .isZero();
        assertThat(snapshotMapper.releaseLock("owner-a")).isEqualTo(1);
        assertThat(snapshotMapper.tryAcquireLock("owner-b", now, now.plus(Duration.ofMinutes(5))))
                .isEqualTo(1);
        snapshotMapper.releaseLock("owner-b");
    }

    private List<CmsImportFile> files(Path directory, Path affiliationOverride) {
        return List.of(
                CmsImportFile.local(
                        CmsDataset.HOSPITAL_GENERAL,
                        directory.resolve(CmsDataset.HOSPITAL_GENERAL.defaultFileName())),
                CmsImportFile.local(
                        CmsDataset.DOCTOR_NATIONAL,
                        directory.resolve(CmsDataset.DOCTOR_NATIONAL.defaultFileName())),
                CmsImportFile.local(
                        CmsDataset.FACILITY_AFFILIATION,
                        affiliationOverride == null
                                ? directory.resolve(CmsDataset.FACILITY_AFFILIATION.defaultFileName())
                                : affiliationOverride));
    }
}
