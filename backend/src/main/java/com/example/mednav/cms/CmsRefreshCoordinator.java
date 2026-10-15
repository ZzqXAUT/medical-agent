package com.example.mednav.cms;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CmsRefreshCoordinator {

    private static final Logger log = LoggerFactory.getLogger(CmsRefreshCoordinator.class);

    private final CmsSnapshotMapper snapshotMapper;
    private final CmsMetastoreClient metastoreClient;
    private final CmsSnapshotImportService importService;
    private final Duration lockLease;

    public CmsRefreshCoordinator(
            CmsSnapshotMapper snapshotMapper,
            CmsMetastoreClient metastoreClient,
            CmsSnapshotImportService importService,
            @Value("${mednav.cms.lock-lease:30m}") Duration lockLease) {
        this.snapshotMapper = snapshotMapper;
        this.metastoreClient = metastoreClient;
        this.importService = importService;
        this.lockLease = lockLease;
    }

    public CmsImportResult refresh() {
        String owner = "cms-" + UUID.randomUUID();
        Instant now = Instant.now();
        if (snapshotMapper.tryAcquireLock(owner, now, now.plus(lockLease)) != 1) {
            log.info("cms_refresh_skipped reason=LOCK_HELD");
            return new CmsImportResult(null, "LOCKED", Map.of(), 0, 0, 0, 0);
        }
        String refreshSnapshotId = UUID.randomUUID().toString();
        snapshotMapper.insertSnapshot(refreshSnapshotId, "DOWNLOADING", now);
        try {
            EnumMap<CmsDataset, CmsSourceFileRecord> previous = new EnumMap<>(CmsDataset.class);
            for (CmsSourceFileRecord source : snapshotMapper.findActiveSourceFiles()) {
                for (CmsDataset dataset : CmsDataset.values()) {
                    if (dataset.datasetId().equals(source.datasetId())) {
                        previous.put(dataset, source);
                    }
                }
            }
            List<CmsImportFile> files = new ArrayList<>();
            boolean changed = false;
            for (CmsDataset dataset : CmsDataset.values()) {
                CmsDownloadArtifact artifact =
                        metastoreClient.resolveAndDownload(dataset, previous.get(dataset));
                files.add(artifact.importFile());
                changed |= artifact.changed();
            }
            if (!changed && previous.size() == CmsDataset.values().length) {
                snapshotMapper.markNoChange(refreshSnapshotId);
                log.info("cms_refresh_completed snapshotId={} status=NO_CHANGE", refreshSnapshotId);
                return new CmsImportResult(
                        refreshSnapshotId, "NO_CHANGE", Map.of(), 0, 0, 0, 0);
            }
            return importService.importSnapshot(refreshSnapshotId, now, files);
        } catch (RuntimeException error) {
            String code = error instanceof CmsImportException importError
                    ? importError.code()
                    : "CMS_REFRESH_FAILED";
            String detail = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            snapshotMapper.markFailed(
                    refreshSnapshotId,
                    code,
                    detail.substring(0, Math.min(detail.length(), 255)));
            log.warn(
                    "cms_refresh_failed snapshotId={} code={} errorType={}",
                    refreshSnapshotId,
                    code,
                    error.getClass().getSimpleName());
            throw error;
        } finally {
            snapshotMapper.releaseLock(owner);
        }
    }
}
