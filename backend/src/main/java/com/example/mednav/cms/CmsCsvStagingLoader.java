package com.example.mednav.cms;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Streams a source file into snapshot-scoped staging tables without retaining the file in memory. */
@Service
public class CmsCsvStagingLoader {

    private static final Pattern NPI = Pattern.compile("\\d{10}");
    private static final Pattern FACILITY_ID = Pattern.compile("[0-9A-Z]{6}");

    private static final List<String> HOSPITAL_COLUMNS = List.of(
            "snapshot_id", "source_row_number", "facility_id", "facility_name", "address",
            "city", "state", "zip_code", "county", "telephone", "hospital_type",
            "hospital_ownership", "emergency_services", "birthing_friendly", "overall_rating",
            "overall_rating_footnote", "mort_group_measure_count", "mort_facility_measure_count",
            "mort_better", "mort_same", "mort_worse", "mort_footnote",
            "safety_group_measure_count", "safety_facility_measure_count", "safety_better",
            "safety_same", "safety_worse", "safety_footnote", "readm_group_measure_count",
            "readm_facility_measure_count", "readm_better", "readm_same", "readm_worse",
            "readm_footnote", "patient_experience_group_measure_count",
            "patient_experience_facility_measure_count", "patient_experience_footnote",
            "timely_effective_group_measure_count", "timely_effective_facility_measure_count",
            "timely_effective_footnote");
    private static final List<String> DOCTOR_COLUMNS = List.of(
            "snapshot_id", "source_row_number", "npi", "ind_pac_id", "ind_enrl_id",
            "last_name", "first_name", "middle_name", "suffix", "gender_code", "credential",
            "medical_school", "graduation_year", "primary_specialty", "secondary_specialty_1",
            "secondary_specialty_2", "secondary_specialty_3", "secondary_specialty_4",
            "secondary_specialty_all", "telehealth", "facility_name", "org_pac_id",
            "organization_member_count", "address_line_1", "address_line_2",
            "line_2_suppressed", "city", "state", "zip_code", "telephone",
            "individual_assignment", "group_assignment", "adrs_id");
    private static final List<String> SPECIALTY_COLUMNS = List.of(
            "snapshot_id", "source_row_number", "npi", "specialty_type", "source_ordinal",
            "specialty_name", "normalized_specialty");
    private static final List<String> AFFILIATION_COLUMNS = List.of(
            "snapshot_id", "source_row_number", "npi", "ind_pac_id", "facility_type",
            "facility_id");

    private final JdbcTemplate jdbcTemplate;
    private final int batchSize;

    public CmsCsvStagingLoader(
            JdbcTemplate jdbcTemplate,
            @Value("${mednav.cms.batch-size:1000}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.batchSize = batchSize;
    }

    public CmsCsvLoadResult load(String snapshotId, CmsImportFile source) {
        if (!Files.isRegularFile(source.path())) {
            throw new CmsImportException(
                    "CMS_SOURCE_MISSING", "CMS source file does not exist: " + source.path());
        }
        MessageDigest digest = sha256Digest();
        long rowCount = 0;
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(source.path()), digest);
                PushbackReader reader = withoutBom(new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8)));
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true)
                        .get()
                        .parse(reader)) {
            validateHeaders(source.dataset(), parser.getHeaderNames());
            rowCount = switch (source.dataset()) {
                case HOSPITAL_GENERAL -> loadHospitals(snapshotId, parser);
                case DOCTOR_NATIONAL -> loadDoctors(snapshotId, parser);
                case FACILITY_AFFILIATION -> loadAffiliations(snapshotId, parser);
            };
        } catch (CmsImportException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CmsImportException(
                    "CMS_CSV_READ_FAILED", "Unable to read " + source.dataset().logicalName(), exception);
        } catch (DataAccessException exception) {
            throw new CmsImportException(
                    "CMS_STAGE_WRITE_FAILED",
                    "Unable to stage " + source.dataset().logicalName(),
                    exception);
        } catch (RuntimeException exception) {
            throw new CmsImportException(
                    "CMS_TYPE_DRIFT",
                    "Invalid value in " + source.dataset().logicalName() + ": " + exception.getMessage(),
                    exception);
        }

        String actualSha = HexFormat.of().formatHex(digest.digest());
        if (source.expectedSha256() != null
                && !actualSha.equalsIgnoreCase(source.expectedSha256())) {
            throw new CmsImportException(
                    "CMS_CHECKSUM_MISMATCH",
                    "Checksum mismatch for " + source.dataset().logicalName());
        }
        if (rowCount == 0) {
            throw new CmsImportException(
                    "CMS_EMPTY_DATASET", source.dataset().logicalName() + " contains no rows");
        }
        return new CmsCsvLoadResult(rowCount, actualSha);
    }

    private long loadHospitals(String snapshotId, CSVParser parser) {
        String sql = insertSql("cms_stage_hospital", HOSPITAL_COLUMNS);
        List<Object[]> batch = new ArrayList<>(batchSize);
        long row = 0;
        for (CSVRecord record : parser) {
            row++;
            String facilityId = requiredId(record, "Facility ID", FACILITY_ID);
            batch.add(new Object[] {
                snapshotId,
                row,
                facilityId,
                required(record, "Facility Name", 128),
                required(record, "Address", 160),
                required(record, "City/Town", 64),
                required(record, "State", 2).toUpperCase(Locale.ROOT),
                required(record, "ZIP Code", 10),
                optional(record, "County/Parish", 64),
                optional(record, "Telephone Number", 24),
                optional(record, "Hospital Type", 64),
                optional(record, "Hospital Ownership", 96),
                nullableBoolean(record, "Emergency Services"),
                nullableBoolean(record, "Meets criteria for birthing friendly designation"),
                nullableInteger(record, "Hospital overall rating", 1, 5),
                optional(record, "Hospital overall rating footnote", 64),
                nullableInteger(record, "MORT Group Measure Count", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of Facility MORT Measures", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of MORT Measures Better", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of MORT Measures No Different", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of MORT Measures Worse", 0, Integer.MAX_VALUE),
                optional(record, "MORT Group Footnote", 64),
                nullableInteger(record, "Safety Group Measure Count", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of Facility Safety Measures", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of Safety Measures Better", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of Safety Measures No Different", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of Safety Measures Worse", 0, Integer.MAX_VALUE),
                optional(record, "Safety Group Footnote", 64),
                nullableInteger(record, "READM Group Measure Count", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of Facility READM Measures", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of READM Measures Better", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of READM Measures No Different", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of READM Measures Worse", 0, Integer.MAX_VALUE),
                optional(record, "READM Group Footnote", 64),
                nullableInteger(record, "Pt Exp Group Measure Count", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of Facility Pt Exp Measures", 0, Integer.MAX_VALUE),
                optional(record, "Pt Exp Group Footnote", 64),
                nullableInteger(record, "TE Group Measure Count", 0, Integer.MAX_VALUE),
                nullableInteger(record, "Count of Facility TE Measures", 0, Integer.MAX_VALUE),
                optional(record, "TE Group Footnote", 64)
            });
            flushWhenFull(sql, batch);
        }
        flush(sql, batch);
        return row;
    }

    private long loadDoctors(String snapshotId, CSVParser parser) {
        String doctorSql = insertSql("cms_stage_doctor_record", DOCTOR_COLUMNS);
        String specialtySql = insertSql("cms_stage_doctor_specialty", SPECIALTY_COLUMNS);
        List<Object[]> doctorBatch = new ArrayList<>(batchSize);
        List<Object[]> specialtyBatch = new ArrayList<>(batchSize);
        long row = 0;
        for (CSVRecord record : parser) {
            row++;
            String npi = requiredId(record, "NPI", NPI);
            String primary = optional(record, "pri_spec", 160);
            List<String> secondaries = secondarySpecialties(record);
            doctorBatch.add(new Object[] {
                snapshotId,
                row,
                npi,
                optional(record, "Ind_PAC_ID", 10),
                optional(record, "Ind_enrl_ID", 32),
                required(record, "Provider Last Name", 80),
                required(record, "Provider First Name", 80),
                optional(record, "Provider Middle Name", 80),
                optional(record, "suff", 20),
                optional(record, "gndr", 8),
                optional(record, "Cred", 64),
                optional(record, "Med_sch", 160),
                nullableInteger(record, "Grd_yr", 1800, 2200),
                primary,
                optional(record, "sec_spec_1", 160),
                optional(record, "sec_spec_2", 160),
                optional(record, "sec_spec_3", 160),
                optional(record, "sec_spec_4", 160),
                optional(record, "sec_spec_all", 640),
                nullableBoolean(record, "Telehlth"),
                optional(record, "Facility Name", 160),
                optional(record, "org_pac_id", 16),
                nullableInteger(record, "num_org_mem", 0, Integer.MAX_VALUE),
                optional(record, "adr_ln_1", 160),
                optional(record, "adr_ln_2", 160),
                nullableBoolean(record, "ln_2_sprs"),
                optional(record, "City/Town", 64),
                upperOptional(record, "State", 2),
                optional(record, "ZIP Code", 10),
                optional(record, "Telephone Number", 24),
                assignmentFlag(record, "ind_assgn"),
                assignmentFlag(record, "grp_assgn"),
                optional(record, "adrs_id", 32)
            });
            if (primary != null) {
                specialtyBatch.add(specialty(snapshotId, row, npi, "PRIMARY", 1, primary));
            }
            short ordinal = 1;
            for (String secondary : secondaries) {
                specialtyBatch.add(
                        specialty(snapshotId, row, npi, "SECONDARY", ordinal++, secondary));
            }
            flushWhenFull(doctorSql, doctorBatch);
            flushWhenFull(specialtySql, specialtyBatch);
        }
        flush(doctorSql, doctorBatch);
        flush(specialtySql, specialtyBatch);
        return row;
    }

    private long loadAffiliations(String snapshotId, CSVParser parser) {
        String sql = insertSql("cms_stage_affiliation", AFFILIATION_COLUMNS);
        List<Object[]> batch = new ArrayList<>(batchSize);
        long row = 0;
        for (CSVRecord record : parser) {
            row++;
            batch.add(new Object[] {
                snapshotId,
                row,
                requiredId(record, "NPI", NPI),
                optional(record, "Ind_PAC_ID", 10),
                optional(record, "facility_type", 64),
                requiredId(
                        record,
                        "Facility Affiliations Certification Number",
                        FACILITY_ID)
            });
            flushWhenFull(sql, batch);
        }
        flush(sql, batch);
        return row;
    }

    private Object[] specialty(
            String snapshotId,
            long row,
            String npi,
            String type,
            int ordinal,
            String name) {
        return new Object[] {
            snapshotId, row, npi, type, (short) ordinal, name, normalizeSpecialty(name)
        };
    }

    private List<String> secondarySpecialties(CSVRecord record) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index <= 4; index++) {
            String value = optional(record, "sec_spec_" + index, 160);
            if (value != null) {
                values.putIfAbsent(normalizeSpecialty(value), value);
            }
        }
        if (values.isEmpty()) {
            String all = optional(record, "sec_spec_all", 640);
            if (all != null) {
                for (String value : all.split("\\s*(?:,|;|\\|)\\s*")) {
                    if (!value.isBlank()) {
                        String bounded = bounded(value.strip(), "sec_spec_all item", 160);
                        values.putIfAbsent(normalizeSpecialty(bounded), bounded);
                    }
                }
            }
        }
        return List.copyOf(values.values());
    }

    private void validateHeaders(CmsDataset dataset, List<String> actualHeaders) {
        List<String> normalized = new ArrayList<>(actualHeaders);
        if (!normalized.isEmpty()) {
            normalized.set(0, normalized.get(0).replaceFirst("^\\uFEFF", ""));
        }
        if (!dataset.expectedHeaders().equals(normalized)) {
            throw new CmsImportException(
                    "CMS_SCHEMA_DRIFT",
                    dataset.logicalName() + " header mismatch; expected="
                            + dataset.expectedHeaders() + ", actual=" + normalized);
        }
    }

    private PushbackReader withoutBom(BufferedReader delegate) throws IOException {
        PushbackReader reader = new PushbackReader(delegate, 1);
        int first = reader.read();
        if (first != -1 && first != '\uFEFF') {
            reader.unread(first);
        }
        return reader;
    }

    private String requiredId(CSVRecord record, String field, Pattern pattern) {
        String value = required(record, field, 32).toUpperCase(Locale.ROOT);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has invalid identifier: " + value);
        }
        return value;
    }

    private String required(CSVRecord record, String field, int maxLength) {
        String value = optional(record, field, maxLength);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required at record " + record.getRecordNumber());
        }
        return value;
    }

    private String optional(CSVRecord record, String field, int maxLength) {
        String value = record.get(field).strip();
        return value.isEmpty() ? null : bounded(value, field, maxLength);
    }

    private String upperOptional(CSVRecord record, String field, int maxLength) {
        String value = optional(record, field, maxLength);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private String bounded(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    private Integer nullableInteger(CSVRecord record, String field, int min, int max) {
        String value = optional(record, field, 32);
        if (value == null
                || value.equalsIgnoreCase("Not Available")
                || value.equalsIgnoreCase("Not Applicable")) {
            return null;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(field + " is outside the accepted range");
        }
        return parsed;
    }

    private Boolean nullableBoolean(CSVRecord record, String field) {
        String value = optional(record, field, 16);
        if (value == null || value.equalsIgnoreCase("Not Available")) {
            return null;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "Y", "YES", "TRUE" -> true;
            case "N", "NO", "FALSE" -> false;
            default -> throw new IllegalArgumentException(field + " has invalid boolean value " + value);
        };
    }

    private Boolean assignmentFlag(CSVRecord record, String field) {
        String value = optional(record, field, 16);
        if (value == null) {
            return null;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "Y" -> true;
            case "N" -> false;
            // CMS currently also emits M/O assignment categories; they are retained as unknown
            // because this field is not a recommendation criterion.
            case "M", "O" -> null;
            default -> throw new IllegalArgumentException(field + " has unknown assignment code " + value);
        };
    }

    private String normalizeSpecialty(String value) {
        return value.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String insertSql(String table, List<String> columns) {
        return "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(columns.size(), "?")) + ")";
    }

    private void flushWhenFull(String sql, List<Object[]> batch) {
        if (batch.size() >= batchSize) {
            flush(sql, batch);
        }
    }

    private void flush(String sql, List<Object[]> batch) {
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
            batch.clear();
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
