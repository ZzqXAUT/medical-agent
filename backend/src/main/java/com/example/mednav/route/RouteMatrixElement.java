package com.example.mednav.route;

public record RouteMatrixElement(
        String facilityId,
        TravelMode mode,
        RouteElementStatus status,
        Integer distanceMeters,
        Long durationSeconds,
        String errorCode) {

    public RouteMatrixElement {
        if (facilityId == null || facilityId.isBlank() || mode == null || status == null) {
            throw new IllegalArgumentException("facilityId, mode and status are required");
        }
        if (status == RouteElementStatus.SUCCESS
                && (distanceMeters == null
                        || distanceMeters < 0
                        || durationSeconds == null
                        || durationSeconds < 0)) {
            throw new IllegalArgumentException("Successful route elements require distance and duration");
        }
    }
}
