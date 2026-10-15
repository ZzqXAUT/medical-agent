package com.example.mednav.location;

import java.time.Instant;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(
        name = "mednav.maps.mode",
        havingValue = "stub",
        matchIfMissing = true)
public class StubGeocodingClient implements GeocodingClient {

    @Override
    public Mono<LocationResult> reverseGeocode(GeoPoint point) {
        return Mono.error(new LocationException(
                "STUB_REVERSE_GEOCODING_UNAVAILABLE",
                "Browser coordinates require MAPS_MODE=google; use a supported manual fixture address.",
                false));
    }

    @Override
    public Mono<LocationResult> geocodeAddress(String address) {
        String normalized = address == null ? "" : address.strip();
        if (normalized.isEmpty()) {
            return Mono.error(new LocationException(
                    "LOCATION_ADDRESS_REQUIRED", "A manual address or ZIP code is required.", true));
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.matches(".*\\b35957\\b.*") || upper.contains("BOAZ")) {
            return Mono.just(result(
                    normalized, new GeoPoint(34.20065, -86.16637), "Boaz", "AL", "35957"));
        }
        if (upper.matches(".*\\b36301\\b.*") || upper.contains("DOTHAN")) {
            return Mono.just(result(
                    normalized, new GeoPoint(31.21470, -85.36137), "Dothan", "AL", "36301"));
        }
        return Mono.error(new LocationException(
                "LOCATION_NOT_RESOLVED",
                "Local Stub only recognizes Dothan, AL 36301 or Boaz, AL 35957.",
                true));
    }

    private LocationResult result(
            String address, GeoPoint point, String city, String state, String postalCode) {
        return new LocationResult(
                point,
                address,
                city,
                state,
                postalCode,
                "US",
                "stub-manual-" + postalCode,
                LocationSource.MANUAL,
                Instant.now());
    }
}
