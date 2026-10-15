package com.example.mednav.route;

import java.time.Instant;

public record HospitalRoute(
        String snapshotId,
        String hospitalId,
        RouteMeasure drive,
        RouteMeasure transit,
        RouteMeasure walk,
        Instant calculatedAt,
        RouteElementStatus status) {}
