package com.example.mednav.observability;

import com.example.mednav.cms.CmsSnapshotMapper;
import com.example.mednav.cms.CmsSnapshotSummary;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("cmsSnapshot")
public class CmsSnapshotHealthIndicator extends AbstractHealthIndicator {

    private final CmsSnapshotMapper snapshotMapper;
    private final JdbcTemplate jdbcTemplate;

    public CmsSnapshotHealthIndicator(
            CmsSnapshotMapper snapshotMapper, JdbcTemplate jdbcTemplate) {
        this.snapshotMapper = snapshotMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        CmsSnapshotSummary active = snapshotMapper.findActiveSnapshot();
        if (active == null) {
            builder.up().withDetail("available", false);
            return;
        }
        builder.up()
                .withDetail("available", true)
                .withDetail("snapshotId", active.snapshotId())
                .withDetail("publishedAt", active.publishedAt())
                .withDetail("hospitals", count("cms_hospital", active.snapshotId()))
                .withDetail("doctors", count("cms_doctor", active.snapshotId()))
                .withDetail("affiliations", count(
                        "cms_doctor_hospital_affiliation", active.snapshotId()));
    }

    private long count(String table, String snapshotId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE snapshot_id = ?",
                Long.class,
                snapshotId);
        return value == null ? 0 : value;
    }
}
