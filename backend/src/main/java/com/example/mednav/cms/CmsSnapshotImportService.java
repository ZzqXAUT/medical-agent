package com.example.mednav.cms;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CmsSnapshotImportService {

    private static final Logger log = LoggerFactory.getLogger(CmsSnapshotImportService.class);

    private final CmsSnapshotMapper snapshotMapper;
    private final CmsCsvStagingLoader stagingLoader;
    private final CmsSnapshotPublisher publisher;
    private final CmsStagingCleaner stagingCleaner;

    public CmsSnapshotImportService(
            CmsSnapshotMapper snapshotMapper,
            CmsCsvStagingLoader stagingLoader,
            CmsSnapshotPublisher publisher,
            CmsStagingCleaner stagingCleaner) {
        this.snapshotMapper = snapshotMapper;
        this.stagingLoader = stagingLoader;
        this.publisher = publisher;
        this.stagingCleaner = stagingCleaner;
    }

    public CmsImportResult importSnapshot(List<CmsImportFile> files) {
        validateFileSet(files);
        String snapshotId = UUID.randomUUID().toString();
        Instant started = Instant.now();
        snapshotMapper.insertSnapshot(snapshotId, "VALIDATING", started);
        return importSnapshot(snapshotId, started, files);
    }

    CmsImportResult importSnapshot(
            String snapshotId, Instant started, List<CmsImportFile> files) {
        Map<String, Long> sourceRows = new java.util.LinkedHashMap<>();
        try {
            EnumMap<CmsDataset, CmsImportFile> byDataset = validateFileSet(files);
            snapshotMapper.markValidating(snapshotId);
            // Load referenced entities before affiliation so failures are easier to interpret.
            for (CmsDataset dataset : List.of(
                    CmsDataset.HOSPITAL_GENERAL,
                    CmsDataset.DOCTOR_NATIONAL,
                    CmsDataset.FACILITY_AFFILIATION)) {
                CmsImportFile source = byDataset.get(dataset);
                CmsCsvLoadResult loaded = stagingLoader.load(snapshotId, source);
                sourceRows.put(dataset.logicalName(), loaded.rowCount());
                snapshotMapper.insertSourceFile(new CmsSourceFileRecord(
                        snapshotId,
                        dataset.datasetId(),
                        dataset.logicalName(),
                        source.sourceModifiedAt(),
                        source.sourceReleasedAt(),
                        source.resolvedDownloadUrl(),
                        source.etag(),
                        loaded.sha256(),
                        loaded.rowCount(),
                        source.path().toAbsolutePath().toString()));
                log.info(
                        "cms_source_staged snapshotId={} datasetId={} logicalName={} rows={} sha256={}",
                        snapshotId,
                        dataset.datasetId(),
                        dataset.logicalName(),
                        loaded.rowCount(),
                        loaded.sha256());
            }
            CmsPublishStats stats = publisher.validateNormalizeAndPublish(snapshotId);
            log.info(
                    "cms_import_completed snapshotId={} elapsedMs={} status=ACTIVE",
                    snapshotId,
                    Duration.between(started, Instant.now()).toMillis());
            cleanupBestEffort();
            return new CmsImportResult(
                    snapshotId,
                    "ACTIVE",
                    sourceRows,
                    stats.hospitals(),
                    stats.doctors(),
                    stats.specialties(),
                    stats.affiliations());
        } catch (RuntimeException error) {
            String code = error instanceof CmsImportException importError
                    ? importError.code()
                    : "CMS_IMPORT_FAILED";
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (detail.length() > 255) {
                detail = detail.substring(0, 255);
            }
            snapshotMapper.markFailed(snapshotId, code, detail);
            cleanupBestEffort();
            log.warn(
                    "cms_import_failed snapshotId={} code={} errorType={} elapsedMs={}",
                    snapshotId,
                    code,
                    error.getClass().getSimpleName(),
                    Duration.between(started, Instant.now()).toMillis());
            throw error;
        }
    }

    private EnumMap<CmsDataset, CmsImportFile> validateFileSet(List<CmsImportFile> files) {
        if (files == null) {
            throw new IllegalArgumentException("CMS import files are required");
        }
        EnumMap<CmsDataset, CmsImportFile> byDataset = new EnumMap<>(CmsDataset.class);
        for (CmsImportFile file : files) {
            if (file == null) {
                throw new IllegalArgumentException("CMS import file cannot be null");
            }
            if (byDataset.put(file.dataset(), file) != null) {
                throw new IllegalArgumentException("Duplicate CMS dataset: " + file.dataset());
            }
        }
        if (byDataset.size() != CmsDataset.values().length) {
            throw new IllegalArgumentException("A CMS snapshot requires all three datasets");
        }
        return byDataset;
    }

    private void cleanupBestEffort() {
        try {
            stagingCleaner.cleanupCompletedSnapshots();
        } catch (RuntimeException cleanupError) {
            log.warn(
                    "cms_staging_cleanup_failed errorType={}",
                    cleanupError.getClass().getSimpleName());
        }
    }
}
