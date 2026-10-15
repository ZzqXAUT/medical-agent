package com.example.mednav.cms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CmsStagingCleaner {

    private static final Logger log = LoggerFactory.getLogger(CmsStagingCleaner.class);

    private final JdbcTemplate jdbcTemplate;

    public CmsStagingCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Removes reproducible staging rows while retaining snapshot/source metadata and raw files. */
    @Transactional
    public void cleanupCompletedSnapshots() {
        String completed = "SELECT snapshot_id FROM cms_provider_snapshot "
                + "WHERE status IN ('ACTIVE', 'READY', 'FAILED', 'NO_CHANGE')";
        int specialties = jdbcTemplate.update(
                "DELETE FROM cms_stage_doctor_specialty WHERE snapshot_id IN (" + completed + ")");
        int affiliations = jdbcTemplate.update(
                "DELETE FROM cms_stage_affiliation WHERE snapshot_id IN (" + completed + ")");
        int doctors = jdbcTemplate.update(
                "DELETE FROM cms_stage_doctor_record WHERE snapshot_id IN (" + completed + ")");
        int hospitals = jdbcTemplate.update(
                "DELETE FROM cms_stage_hospital WHERE snapshot_id IN (" + completed + ")");
        log.info(
                "cms_staging_cleaned hospitals={} doctorRows={} specialtyRows={} affiliations={}",
                hospitals,
                doctors,
                specialties,
                affiliations);
    }
}
