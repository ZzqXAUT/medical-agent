package com.example.mednav.api;

import com.example.mednav.location.LocationResult;
import com.example.mednav.location.LocationStatus;
import com.example.mednav.route.RouteMatrixResult;
import java.util.UUID;

public record NavigationRunResponse(
        UUID taskId,
        UUID conversationId,
        long contextVersion,
        long locationRevision,
        long clinicalRevision,
        LocationStatus status,
        LocationResult location,
        RouteMatrixResult routeMatrix,
        String errorCode,
        boolean joinReady) {}
