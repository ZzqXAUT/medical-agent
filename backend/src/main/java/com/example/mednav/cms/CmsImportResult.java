package com.example.mednav.cms;

import java.util.Map;

public record CmsImportResult(
        String snapshotId,
        String status,
        Map<String, Long> sourceRows,
        long hospitals,
        long doctors,
        long specialties,
        long affiliations) {

    public CmsImportResult {
        sourceRows = sourceRows == null ? Map.of() : Map.copyOf(sourceRows);
    }
}
