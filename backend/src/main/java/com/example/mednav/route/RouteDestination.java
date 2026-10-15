package com.example.mednav.route;

public record RouteDestination(String facilityId, String address) {

    public RouteDestination {
        if (facilityId == null || facilityId.isBlank() || address == null || address.isBlank()) {
            throw new IllegalArgumentException("facilityId and address are required");
        }
        facilityId = facilityId.strip();
        address = address.strip();
    }
}
