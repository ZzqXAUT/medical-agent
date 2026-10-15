package com.example.mednav.route;

import com.example.mednav.location.GeoPoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RouteResultCache {

    private final ConcurrentHashMap<CacheKey, CachedRoute> routes = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final String parametersVersion;
    private final Clock clock;

    @Autowired
    public RouteResultCache(
            @Value("${mednav.maps.route.cache-ttl:10m}") Duration ttl,
            @Value("${mednav.maps.route.parameters-version:route-v1}") String parametersVersion) {
        this(ttl, parametersVersion, Clock.systemUTC());
    }

    RouteResultCache(Duration ttl, String parametersVersion, Clock clock) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Route cache TTL must be positive");
        }
        this.ttl = ttl;
        this.parametersVersion = parametersVersion;
        this.clock = clock;
    }

    public Optional<RouteMatrixElement> get(
            GeoPoint origin, String snapshotId, String facilityId, TravelMode mode) {
        CacheKey key = key(origin, snapshotId, facilityId, mode);
        CachedRoute cached = routes.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (!cached.expiresAt().isAfter(clock.instant())) {
            routes.remove(key, cached);
            return Optional.empty();
        }
        return Optional.of(cached.element());
    }

    public void put(
            GeoPoint origin, String snapshotId, RouteMatrixElement element) {
        routes.put(
                key(origin, snapshotId, element.facilityId(), element.mode()),
                new CachedRoute(element, clock.instant().plus(ttl)));
    }

    private CacheKey key(
            GeoPoint origin, String snapshotId, String facilityId, TravelMode mode) {
        long normalizedLatitude = Math.round(origin.latitude() * 100_000);
        long normalizedLongitude = Math.round(origin.longitude() * 100_000);
        return new CacheKey(
                normalizedLatitude,
                normalizedLongitude,
                snapshotId,
                facilityId,
                mode,
                parametersVersion);
    }

    private record CacheKey(
            long latitudeE5,
            long longitudeE5,
            String snapshotId,
            String facilityId,
            TravelMode mode,
            String parametersVersion) {}

    private record CachedRoute(RouteMatrixElement element, Instant expiresAt) {}
}
