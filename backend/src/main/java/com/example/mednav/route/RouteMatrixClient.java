package com.example.mednav.route;

import com.example.mednav.location.GeoPoint;
import java.util.List;
import reactor.core.publisher.Mono;

public interface RouteMatrixClient {

    Mono<List<RouteMatrixElement>> compute(
            GeoPoint origin, List<RouteDestination> destinations, TravelMode mode);
}
