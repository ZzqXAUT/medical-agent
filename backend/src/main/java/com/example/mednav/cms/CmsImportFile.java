package com.example.mednav.cms;

import java.nio.file.Path;
import java.time.Instant;

public record CmsImportFile(
        CmsDataset dataset,
        Path path,
        String resolvedDownloadUrl,
        String etag,
        Instant sourceModifiedAt,
        Instant sourceReleasedAt,
        String expectedSha256) {

    public CmsImportFile {
        if (dataset == null || path == null) {
            throw new IllegalArgumentException("dataset and path are required");
        }
        resolvedDownloadUrl = resolvedDownloadUrl == null
                ? path.toAbsolutePath().toUri().toString()
                : resolvedDownloadUrl;
    }

    public static CmsImportFile local(CmsDataset dataset, Path path) {
        return new CmsImportFile(dataset, path, null, null, null, null, null);
    }
}
