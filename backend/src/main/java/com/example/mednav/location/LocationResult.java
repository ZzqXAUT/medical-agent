package com.example.mednav.location;

import java.time.Instant;

public record LocationResult(
        GeoPoint point,
        String formattedAddress,
        String city,
        String state,
        String postalCode,
        String countryCode,
        String placeId,
        LocationSource source,
        Instant resolvedAt) {

    public LocationResult {
        if (point == null || source == null || resolvedAt == null) {
            throw new IllegalArgumentException("point, source and resolvedAt are required");
        }
        formattedAddress = required(formattedAddress, "formattedAddress");
        city = required(city, "city");
        state = required(state, "state").toUpperCase();
        countryCode = required(countryCode, "countryCode").toUpperCase();
        if (state.length() != 2 || countryCode.length() != 2) {
            throw new IllegalArgumentException("state and countryCode must use two-letter codes");
        }
        if (!"US".equals(countryCode)) {
            throw new IllegalArgumentException("M5 currently supports US locations only");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}
