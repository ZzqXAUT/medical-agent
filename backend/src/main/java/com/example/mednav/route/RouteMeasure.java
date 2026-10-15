package com.example.mednav.route;

import java.math.BigDecimal;

public record RouteMeasure(BigDecimal distanceMiles, long durationMinutes) {

    public RouteMeasure {
        if (distanceMiles == null || distanceMiles.signum() < 0 || durationMinutes < 0) {
            throw new IllegalArgumentException("Route distance and duration must be non-negative");
        }
    }
}
