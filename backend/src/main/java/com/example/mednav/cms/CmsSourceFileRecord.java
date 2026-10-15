package com.example.mednav.cms;

import java.time.Instant;

public record CmsSourceFileRecord(
        String snapshotId,
        String datasetId,
        String logicalName,
        Instant sourceModifiedAt,
        Instant sourceReleasedAt,
        String resolvedDownloadUrl,
        String etag,
        String sha256,
        long rowCount,
        String rawObjectKey) {}
