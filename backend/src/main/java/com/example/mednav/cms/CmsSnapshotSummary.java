package com.example.mednav.cms;

import java.time.Instant;

public record CmsSnapshotSummary(
        String snapshotId,
        String status,
        Instant scheduledAt,
        Instant publishedAt,
        String failureCode,
        String failureDetail) {}
