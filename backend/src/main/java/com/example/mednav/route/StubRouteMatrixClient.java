package com.example.mednav.route;

import com.example.mednav.location.GeoPoint;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(
        name = "mednav.maps.mode",
        havingValue = "stub",
        matchIfMissing = true)
public class StubRouteMatrixClient implements RouteMatrixClient {

    @Override
    public Mono<List<RouteMatrixElement>> compute(
            GeoPoint origin, List<RouteDestination> destinations, TravelMode mode) {
        List<RouteMatrixElement> result = new ArrayList<>(destinations.size());
        for (RouteDestination destination : destinations) {
            int baseMeters = 800 + Math.floorMod(destination.facilityId().hashCode(), 22_000);
            double multiplier = switch (mode) {
                case DRIVE -> 1.0;
                case TRANSIT -> 1.15;
                case WALK -> 0.92;
            };
            int distance = (int) Math.round(baseMeters * multiplier);
            long speedMetersPerMinute = switch (mode) {
                case DRIVE -> 650;
                case TRANSIT -> 360;
                case WALK -> 80;
            };
            long seconds = Math.max(60, Math.round((distance * 60.0) / speedMetersPerMinute));
            result.add(new RouteMatrixElement(
                    destination.facilityId(), mode, RouteElementStatus.SUCCESS, distance, seconds, null));
        }
        return Mono.just(List.copyOf(result));
    }
}
