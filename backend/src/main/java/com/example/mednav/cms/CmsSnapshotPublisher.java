package com.example.mednav.cms;

import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CmsSnapshotPublisher {

    private static final Logger log = LoggerFactory.getLogger(CmsSnapshotPublisher.class);

    private final JdbcTemplate jdbcTemplate;
    private final double minimumAffiliationJoinRate;

    public CmsSnapshotPublisher(
            JdbcTemplate jdbcTemplate,
            @Value("${mednav.cms.min-affiliation-join-rate:0.10}")
                    double minimumAffiliationJoinRate) {
        this.jdbcTemplate = jdbcTemplate;
        this.minimumAffiliationJoinRate = minimumAffiliationJoinRate;
    }

    @Transactional
    public CmsPublishStats validateNormalizeAndPublish(String snapshotId) {
        long stagedHospitals = count("cms_stage_hospital", snapshotId);
        long stagedDoctors = count("cms_stage_doctor_record", snapshotId);
        long stagedSpecialties = count("cms_stage_doctor_specialty", snapshotId);
        long stagedAffiliations = count("cms_stage_affiliation", snapshotId);
        if (stagedHospitals == 0 || stagedDoctors == 0 || stagedSpecialties == 0
                || stagedAffiliations == 0) {
            throw new CmsImportException(
                    "CMS_EMPTY_DATASET", "Every CMS source must contain usable rows");
        }

        long unknownDoctors = scalar("""
                SELECT COUNT(*) FROM cms_stage_affiliation a
                WHERE a.snapshot_id = ? AND NOT EXISTS (
                    SELECT 1 FROM cms_stage_doctor_record d
                    WHERE d.snapshot_id = a.snapshot_id AND d.npi = a.npi)
                """, snapshotId);
        long unknownHospitals = scalar("""
                SELECT COUNT(*) FROM cms_stage_affiliation a
                WHERE a.snapshot_id = ? AND NOT EXISTS (
                    SELECT 1 FROM cms_stage_hospital h
                    WHERE h.snapshot_id = a.snapshot_id AND h.facility_id = a.facility_id)
                """, snapshotId);
        long hospitalAffiliations = scalar("""
                SELECT COUNT(*) FROM cms_stage_affiliation
                WHERE snapshot_id = ? AND LOWER(COALESCE(facility_type, '')) = 'hospital'
                """, snapshotId);
        long joinedAffiliations = scalar("""
                SELECT COUNT(*) FROM cms_stage_affiliation a
                WHERE a.snapshot_id = ?
                  AND LOWER(COALESCE(a.facility_type, '')) = 'hospital'
                  AND EXISTS (
                    SELECT 1 FROM cms_stage_doctor_record d
                    WHERE d.snapshot_id = a.snapshot_id AND d.npi = a.npi)
                  AND EXISTS (
                    SELECT 1 FROM cms_stage_hospital h
                    WHERE h.snapshot_id = a.snapshot_id AND h.facility_id = a.facility_id)
                """, snapshotId);
        double affiliationJoinRate = hospitalAffiliations == 0
                ? 0
                : (double) joinedAffiliations / hospitalAffiliations;
        if (joinedAffiliations == 0 || affiliationJoinRate < minimumAffiliationJoinRate) {
            throw new CmsImportException(
                    "CMS_JOIN_INTEGRITY_FAILED",
                    "Hospital affiliation join rate " + affiliationJoinRate
                            + " is below " + minimumAffiliationJoinRate);
        }

        long identityConflicts = scalar("""
                SELECT COUNT(*) FROM (
                    SELECT npi
                    FROM cms_stage_doctor_record
                    WHERE snapshot_id = ?
                    GROUP BY npi
                    HAVING COUNT(DISTINCT (last_name || '|' || first_name || '|'
                        || COALESCE(credential, ''))) > 1
                ) conflicts
                """, snapshotId);
        log.info(
                "cms_normalization_started snapshotId={} stagedHospitals={} stagedDoctorRows={} stagedAffiliations={} hospitalAffiliations={} joinedAffiliations={} joinRate={} unknownDoctors={} unknownHospitals={} identityConflicts={}",
                snapshotId,
                stagedHospitals,
                stagedDoctors,
                stagedAffiliations,
                hospitalAffiliations,
                joinedAffiliations,
                affiliationJoinRate,
                unknownDoctors,
                unknownHospitals,
                identityConflicts);

        jdbcTemplate.update("""
                INSERT INTO cms_hospital (
                    snapshot_id, facility_id, facility_name, address, city, state, zip_code,
                    county, telephone, hospital_type, hospital_ownership, emergency_services,
                    birthing_friendly, overall_rating, overall_rating_footnote,
                    mort_group_measure_count, mort_facility_measure_count, mort_better, mort_same,
                    mort_worse, mort_footnote, safety_group_measure_count,
                    safety_facility_measure_count, safety_better, safety_same, safety_worse,
                    safety_footnote, readm_group_measure_count, readm_facility_measure_count,
                    readm_better, readm_same, readm_worse, readm_footnote,
                    patient_experience_group_measure_count,
                    patient_experience_facility_measure_count, patient_experience_footnote,
                    timely_effective_group_measure_count,
                    timely_effective_facility_measure_count, timely_effective_footnote
                )
                SELECT snapshot_id, facility_id, facility_name, address, city, state, zip_code,
                    county, telephone, hospital_type, hospital_ownership, emergency_services,
                    birthing_friendly, overall_rating, overall_rating_footnote,
                    mort_group_measure_count, mort_facility_measure_count, mort_better, mort_same,
                    mort_worse, mort_footnote, safety_group_measure_count,
                    safety_facility_measure_count, safety_better, safety_same, safety_worse,
                    safety_footnote, readm_group_measure_count, readm_facility_measure_count,
                    readm_better, readm_same, readm_worse, readm_footnote,
                    patient_experience_group_measure_count,
                    patient_experience_facility_measure_count, patient_experience_footnote,
                    timely_effective_group_measure_count,
                    timely_effective_facility_measure_count, timely_effective_footnote
                FROM cms_stage_hospital WHERE snapshot_id = ?
                """, snapshotId);

        jdbcTemplate.update("""
                INSERT INTO cms_doctor (
                    snapshot_id, npi, ind_pac_id, last_name, first_name, middle_name, suffix,
                    gender_code, credential, medical_school, graduation_year
                )
                SELECT snapshot_id, npi, ind_pac_id, last_name, first_name, middle_name, suffix,
                    gender_code, credential, medical_school, graduation_year
                FROM (
                    SELECT d.*, ROW_NUMBER() OVER (
                        PARTITION BY snapshot_id, npi
                        ORDER BY CASE WHEN ind_enrl_id IS NULL THEN 1 ELSE 0 END,
                                 ind_enrl_id, CASE WHEN adrs_id IS NULL THEN 1 ELSE 0 END,
                                 adrs_id, source_row_number
                    ) AS choice
                    FROM cms_stage_doctor_record d WHERE snapshot_id = ?
                ) ranked WHERE choice = 1
                """, snapshotId);

        jdbcTemplate.update("""
                INSERT INTO cms_doctor_specialty (
                    snapshot_id, npi, specialty_type, specialty_ordinal,
                    specialty_name, normalized_specialty
                )
                SELECT snapshot_id, npi, specialty_type,
                       CAST(ROW_NUMBER() OVER (
                           PARTITION BY snapshot_id, npi, specialty_type
                           ORDER BY normalized_specialty) AS SMALLINT),
                       specialty_name, normalized_specialty
                FROM (
                    SELECT DISTINCT s.snapshot_id, s.npi, s.specialty_type,
                                    s.specialty_name, s.normalized_specialty
                    FROM cms_stage_doctor_specialty s
                    JOIN cms_doctor d ON d.snapshot_id = s.snapshot_id AND d.npi = s.npi
                    WHERE s.snapshot_id = ?
                ) unique_specialties
                """, snapshotId);

        jdbcTemplate.update("""
                INSERT INTO cms_doctor_practice (
                    snapshot_id, npi, ind_enrl_id, adrs_id, org_pac_id, facility_name,
                    address_line_1, address_line_2, line_2_suppressed, city, state, zip_code,
                    telephone, telehealth, individual_assignment, group_assignment,
                    organization_member_count
                )
                SELECT snapshot_id, npi, ind_enrl_id, adrs_id, org_pac_id, facility_name,
                    address_line_1, address_line_2, line_2_suppressed, city, state, zip_code,
                    telephone, telehealth, individual_assignment, group_assignment,
                    organization_member_count
                FROM (
                    SELECT p.*, ROW_NUMBER() OVER (
                        PARTITION BY snapshot_id, npi, ind_enrl_id, adrs_id
                        ORDER BY source_row_number) AS choice
                    FROM cms_stage_doctor_record p
                    WHERE snapshot_id = ? AND ind_enrl_id IS NOT NULL AND adrs_id IS NOT NULL
                ) ranked WHERE choice = 1
                """, snapshotId);

        jdbcTemplate.update("""
                INSERT INTO cms_doctor_hospital_affiliation (
                    snapshot_id, npi, facility_id, ind_pac_id, facility_type
                )
                SELECT a.snapshot_id, a.npi, a.facility_id,
                       MIN(a.ind_pac_id), MIN(a.facility_type)
                FROM cms_stage_affiliation a
                JOIN cms_doctor d ON d.snapshot_id = a.snapshot_id AND d.npi = a.npi
                JOIN cms_hospital h
                  ON h.snapshot_id = a.snapshot_id AND h.facility_id = a.facility_id
                WHERE a.snapshot_id = ?
                  AND LOWER(COALESCE(a.facility_type, '')) = 'hospital'
                GROUP BY a.snapshot_id, a.npi, a.facility_id
                """, snapshotId);

        long hospitals = count("cms_hospital", snapshotId);
        long doctors = count("cms_doctor", snapshotId);
        long specialties = count("cms_doctor_specialty", snapshotId);
        long practices = count("cms_doctor_practice", snapshotId);
        long affiliations = count("cms_doctor_hospital_affiliation", snapshotId);
        if (hospitals == 0 || doctors == 0 || specialties == 0 || affiliations == 0) {
            throw new CmsImportException(
                    "CMS_NORMALIZATION_EMPTY", "Normalized snapshot is incomplete");
        }

        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE cms_provider_snapshot SET status = 'READY'
                WHERE status = 'ACTIVE'
                """);
        jdbcTemplate.update("DELETE FROM cms_active_snapshot WHERE singleton_id = 1");
        jdbcTemplate.update(
                "INSERT INTO cms_active_snapshot (singleton_id, snapshot_id, activated_at) VALUES (1, ?, ?)",
                snapshotId,
                Timestamp.from(now));
        jdbcTemplate.update("""
                UPDATE cms_provider_snapshot SET status = 'ACTIVE', published_at = ?
                WHERE snapshot_id = ?
                """, Timestamp.from(now), snapshotId);

        log.info(
                "cms_snapshot_published snapshotId={} hospitals={} doctors={} specialties={} practices={} affiliations={}",
                snapshotId,
                hospitals,
                doctors,
                specialties,
                practices,
                affiliations);
        return new CmsPublishStats(hospitals, doctors, specialties, practices, affiliations);
    }

    private long count(String table, String snapshotId) {
        return scalar("SELECT COUNT(*) FROM " + table + " WHERE snapshot_id = ?", snapshotId);
    }

    private long scalar(String sql, String snapshotId) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, snapshotId);
        return value == null ? 0 : value;
    }
}
