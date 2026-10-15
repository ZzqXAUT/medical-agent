CREATE TABLE cms_provider_snapshot (
    snapshot_id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    failure_code VARCHAR(64),
    failure_detail VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE cms_source_file (
    snapshot_id VARCHAR(36) NOT NULL REFERENCES cms_provider_snapshot(snapshot_id),
    dataset_id VARCHAR(16) NOT NULL,
    logical_name VARCHAR(64) NOT NULL,
    source_modified_at TIMESTAMP WITH TIME ZONE,
    source_released_at TIMESTAMP WITH TIME ZONE,
    resolved_download_url VARCHAR(2048) NOT NULL,
    etag VARCHAR(255),
    sha256 CHAR(64) NOT NULL,
    row_count BIGINT NOT NULL,
    raw_object_key VARCHAR(1024) NOT NULL,
    PRIMARY KEY (snapshot_id, dataset_id)
);

CREATE TABLE cms_stage_hospital (
    snapshot_id VARCHAR(36) NOT NULL REFERENCES cms_provider_snapshot(snapshot_id),
    source_row_number BIGINT NOT NULL,
    facility_id VARCHAR(6) NOT NULL,
    facility_name VARCHAR(128) NOT NULL,
    address VARCHAR(160) NOT NULL,
    city VARCHAR(64) NOT NULL,
    state CHAR(2) NOT NULL,
    zip_code VARCHAR(10) NOT NULL,
    county VARCHAR(64),
    telephone VARCHAR(24),
    hospital_type VARCHAR(64),
    hospital_ownership VARCHAR(96),
    emergency_services BOOLEAN,
    birthing_friendly BOOLEAN,
    overall_rating SMALLINT,
    overall_rating_footnote VARCHAR(64),
    mort_group_measure_count INTEGER,
    mort_facility_measure_count INTEGER,
    mort_better INTEGER,
    mort_same INTEGER,
    mort_worse INTEGER,
    mort_footnote VARCHAR(64),
    safety_group_measure_count INTEGER,
    safety_facility_measure_count INTEGER,
    safety_better INTEGER,
    safety_same INTEGER,
    safety_worse INTEGER,
    safety_footnote VARCHAR(64),
    readm_group_measure_count INTEGER,
    readm_facility_measure_count INTEGER,
    readm_better INTEGER,
    readm_same INTEGER,
    readm_worse INTEGER,
    readm_footnote VARCHAR(64),
    patient_experience_group_measure_count INTEGER,
    patient_experience_facility_measure_count INTEGER,
    patient_experience_footnote VARCHAR(64),
    timely_effective_group_measure_count INTEGER,
    timely_effective_facility_measure_count INTEGER,
    timely_effective_footnote VARCHAR(64),
    PRIMARY KEY (snapshot_id, source_row_number),
    UNIQUE (snapshot_id, facility_id)
);

CREATE TABLE cms_stage_doctor_record (
    snapshot_id VARCHAR(36) NOT NULL REFERENCES cms_provider_snapshot(snapshot_id),
    source_row_number BIGINT NOT NULL,
    npi VARCHAR(10) NOT NULL,
    ind_pac_id VARCHAR(10),
    ind_enrl_id VARCHAR(32),
    last_name VARCHAR(80) NOT NULL,
    first_name VARCHAR(80) NOT NULL,
    middle_name VARCHAR(80),
    suffix VARCHAR(20),
    gender_code VARCHAR(8),
    credential VARCHAR(64),
    medical_school VARCHAR(160),
    graduation_year SMALLINT,
    primary_specialty VARCHAR(160),
    secondary_specialty_1 VARCHAR(160),
    secondary_specialty_2 VARCHAR(160),
    secondary_specialty_3 VARCHAR(160),
    secondary_specialty_4 VARCHAR(160),
    secondary_specialty_all VARCHAR(640),
    telehealth BOOLEAN,
    facility_name VARCHAR(160),
    org_pac_id VARCHAR(16),
    organization_member_count INTEGER,
    address_line_1 VARCHAR(160),
    address_line_2 VARCHAR(160),
    line_2_suppressed BOOLEAN,
    city VARCHAR(64),
    state CHAR(2),
    zip_code VARCHAR(10),
    telephone VARCHAR(24),
    individual_assignment BOOLEAN,
    group_assignment BOOLEAN,
    adrs_id VARCHAR(32),
    PRIMARY KEY (snapshot_id, source_row_number)
);

CREATE TABLE cms_stage_doctor_specialty (
    snapshot_id VARCHAR(36) NOT NULL REFERENCES cms_provider_snapshot(snapshot_id),
    source_row_number BIGINT NOT NULL,
    npi VARCHAR(10) NOT NULL,
    specialty_type VARCHAR(10) NOT NULL,
    source_ordinal SMALLINT NOT NULL,
    specialty_name VARCHAR(160) NOT NULL,
    normalized_specialty VARCHAR(160) NOT NULL,
    PRIMARY KEY (snapshot_id, source_row_number, specialty_type, source_ordinal, normalized_specialty)
);

CREATE TABLE cms_stage_affiliation (
    snapshot_id VARCHAR(36) NOT NULL REFERENCES cms_provider_snapshot(snapshot_id),
    source_row_number BIGINT NOT NULL,
    npi VARCHAR(10) NOT NULL,
    ind_pac_id VARCHAR(10),
    facility_type VARCHAR(64),
    facility_id VARCHAR(6) NOT NULL,
    PRIMARY KEY (snapshot_id, source_row_number)
);

CREATE TABLE cms_hospital (
    snapshot_id VARCHAR(36) NOT NULL REFERENCES cms_provider_snapshot(snapshot_id),
    facility_id VARCHAR(6) NOT NULL,
    facility_name VARCHAR(128) NOT NULL,
    address VARCHAR(160) NOT NULL,
    city VARCHAR(64) NOT NULL,
    state CHAR(2) NOT NULL,
    zip_code VARCHAR(10) NOT NULL,
    county VARCHAR(64),
    telephone VARCHAR(24),
    hospital_type VARCHAR(64),
    hospital_ownership VARCHAR(96),
    emergency_services BOOLEAN,
    birthing_friendly BOOLEAN,
    overall_rating SMALLINT,
    overall_rating_footnote VARCHAR(64),
    mort_group_measure_count INTEGER,
    mort_facility_measure_count INTEGER,
    mort_better INTEGER,
    mort_same INTEGER,
    mort_worse INTEGER,
    mort_footnote VARCHAR(64),
    safety_group_measure_count INTEGER,
    safety_facility_measure_count INTEGER,
    safety_better INTEGER,
    safety_same INTEGER,
    safety_worse INTEGER,
    safety_footnote VARCHAR(64),
    readm_group_measure_count INTEGER,
    readm_facility_measure_count INTEGER,
    readm_better INTEGER,
    readm_same INTEGER,
    readm_worse INTEGER,
    readm_footnote VARCHAR(64),
    patient_experience_group_measure_count INTEGER,
    patient_experience_facility_measure_count INTEGER,
    patient_experience_footnote VARCHAR(64),
    timely_effective_group_measure_count INTEGER,
    timely_effective_facility_measure_count INTEGER,
    timely_effective_footnote VARCHAR(64),
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    geocoded_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (snapshot_id, facility_id),
    CHECK (overall_rating IS NULL OR overall_rating BETWEEN 1 AND 5)
);

CREATE TABLE cms_doctor (
    snapshot_id VARCHAR(36) NOT NULL REFERENCES cms_provider_snapshot(snapshot_id),
    npi VARCHAR(10) NOT NULL,
    ind_pac_id VARCHAR(10),
    last_name VARCHAR(80) NOT NULL,
    first_name VARCHAR(80) NOT NULL,
    middle_name VARCHAR(80),
    suffix VARCHAR(20),
    gender_code VARCHAR(8),
    credential VARCHAR(64),
    medical_school VARCHAR(160),
    graduation_year SMALLINT,
    PRIMARY KEY (snapshot_id, npi)
);

CREATE TABLE cms_doctor_specialty (
    snapshot_id VARCHAR(36) NOT NULL,
    npi VARCHAR(10) NOT NULL,
    specialty_type VARCHAR(10) NOT NULL,
    specialty_ordinal SMALLINT NOT NULL,
    specialty_name VARCHAR(160) NOT NULL,
    normalized_specialty VARCHAR(160) NOT NULL,
    PRIMARY KEY (snapshot_id, npi, specialty_type, specialty_ordinal),
    FOREIGN KEY (snapshot_id, npi) REFERENCES cms_doctor(snapshot_id, npi)
);

CREATE TABLE cms_doctor_practice (
    snapshot_id VARCHAR(36) NOT NULL,
    npi VARCHAR(10) NOT NULL,
    ind_enrl_id VARCHAR(32) NOT NULL,
    adrs_id VARCHAR(32) NOT NULL,
    org_pac_id VARCHAR(16),
    facility_name VARCHAR(160),
    address_line_1 VARCHAR(160),
    address_line_2 VARCHAR(160),
    line_2_suppressed BOOLEAN,
    city VARCHAR(64),
    state CHAR(2),
    zip_code VARCHAR(10),
    telephone VARCHAR(24),
    telehealth BOOLEAN,
    individual_assignment BOOLEAN,
    group_assignment BOOLEAN,
    organization_member_count INTEGER,
    PRIMARY KEY (snapshot_id, npi, ind_enrl_id, adrs_id),
    FOREIGN KEY (snapshot_id, npi) REFERENCES cms_doctor(snapshot_id, npi)
);

CREATE TABLE cms_doctor_hospital_affiliation (
    snapshot_id VARCHAR(36) NOT NULL,
    npi VARCHAR(10) NOT NULL,
    facility_id VARCHAR(6) NOT NULL,
    ind_pac_id VARCHAR(10),
    facility_type VARCHAR(64),
    PRIMARY KEY (snapshot_id, npi, facility_id),
    FOREIGN KEY (snapshot_id, npi) REFERENCES cms_doctor(snapshot_id, npi),
    FOREIGN KEY (snapshot_id, facility_id) REFERENCES cms_hospital(snapshot_id, facility_id)
);

CREATE TABLE cms_active_snapshot (
    singleton_id SMALLINT PRIMARY KEY,
    snapshot_id VARCHAR(36) NOT NULL REFERENCES cms_provider_snapshot(snapshot_id),
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (singleton_id = 1)
);

CREATE TABLE cms_refresh_lock (
    singleton_id SMALLINT PRIMARY KEY,
    lock_owner VARCHAR(64),
    locked_until TIMESTAMP WITH TIME ZONE,
    CHECK (singleton_id = 1)
);

INSERT INTO cms_refresh_lock (singleton_id, lock_owner, locked_until)
VALUES (1, NULL, NULL);

CREATE INDEX idx_cms_hospital_region_rating
    ON cms_hospital(snapshot_id, state, city, overall_rating DESC, facility_id);
CREATE INDEX idx_cms_affiliation_facility
    ON cms_doctor_hospital_affiliation(snapshot_id, facility_id, npi);
CREATE INDEX idx_cms_specialty_lookup
    ON cms_doctor_specialty(snapshot_id, normalized_specialty, npi);
CREATE INDEX idx_cms_stage_doctor_npi
    ON cms_stage_doctor_record(snapshot_id, npi);
CREATE INDEX idx_cms_stage_affiliation_join
    ON cms_stage_affiliation(snapshot_id, facility_id, npi);

UPDATE system_probe
SET probe_value = '3', updated_at = CURRENT_TIMESTAMP
WHERE probe_key = 'schema_version';
