package com.example.mednav.location;

import reactor.core.publisher.Mono;

public interface GeocodingClient {

    Mono<LocationResult> reverseGeocode(GeoPoint point);

    Mono<LocationResult> geocodeAddress(String address);
}
