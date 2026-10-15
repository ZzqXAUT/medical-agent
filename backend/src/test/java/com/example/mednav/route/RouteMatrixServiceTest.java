package com.example.mednav.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mednav.cms.CmsHospital;
import com.example.mednav.location.GeoPoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RouteMatrixServiceTest {

    @Test
    void expiresRouteElementsAtTenMinutes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T12:00:00Z"));
        RouteResultCache cache = new RouteResultCache(Duration.ofMinutes(10), "test-v1", clock);
        GeoPoint origin = new GeoPoint(31.2, -85.4);
        RouteMatrixElement element = new RouteMatrixElement(
                "010001", TravelMode.DRIVE, RouteElementStatus.SUCCESS, 1609, 600L, null);
        cache.put(origin, "snapshot-1", element);

        assertThat(cache.get(origin, "snapshot-1", "010001", TravelMode.DRIVE)).contains(element);
        clock.advance(Duration.ofMinutes(10));
        assertThat(cache.get(origin, "snapshot-1", "010001", TravelMode.DRIVE)).isEmpty();
    }

    @Test
    void coversEveryHospitalAcrossModesAndBatchesInParallelThenUsesCache() {
        TrackingClient client = new TrackingClient();
        RouteResultCache cache = new RouteResultCache(Duration.ofMinutes(10), "test-v1", Clock.systemUTC());
        RouteMatrixService service = new RouteMatrixService(client, cache, 100, 3);
        List<CmsHospital> hospitals = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            hospitals.add(hospital(String.format("%06d", index)));
        }
        hospitals.add(hospitals.get(0));

        RouteMatrixResult first = service.compute(new GeoPoint(31.2, -85.4), hospitals).block();

        assertThat(first).isNotNull();
        assertThat(first.hospitalCount()).isEqualTo(101);
        assertThat(first.routeElementCount()).isEqualTo(303);
        assertThat(first.batchCount()).isEqualTo(6);
        assertThat(first.cacheMisses()).isEqualTo(303);
        assertThat(first.routes()).hasSize(101).allSatisfy(route -> {
            assertThat(route.drive()).isNotNull();
            assertThat(route.transit()).isNotNull();
            assertThat(route.walk()).isNotNull();
        });
        assertThat(client.maximumActive.get()).isGreaterThan(1);
        assertThat(client.calls.get()).isEqualTo(6);

        RouteMatrixResult second = service.compute(new GeoPoint(31.2, -85.4), hospitals).block();
        assertThat(second).isNotNull();
        assertThat(second.batchCount()).isZero();
        assertThat(second.cacheHits()).isEqualTo(303);
        assertThat(client.calls.get()).isEqualTo(6);
    }

    private CmsHospital hospital(String facilityId) {
        return new CmsHospital(
                "11111111-1111-1111-1111-111111111111",
                facilityId,
                "Hospital " + facilityId,
                "100 Main St",
                "Dothan",
                "AL",
                "36301",
                "Houston",
                null,
                "Acute Care Hospitals",
                null,
                true,
                null,
                4,
                null);
    }

    private static final class TrackingClient implements RouteMatrixClient {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Mono<List<RouteMatrixElement>> compute(
                GeoPoint origin, List<RouteDestination> destinations, TravelMode mode) {
            return Mono.defer(() -> {
                        calls.incrementAndGet();
                        int now = active.incrementAndGet();
                        maximumActive.accumulateAndGet(now, Math::max);
                        return Mono.delay(Duration.ofMillis(20)).map(ignored -> destinations.stream()
                                .map(destination -> new RouteMatrixElement(
                                        destination.facilityId(),
                                        mode,
                                        RouteElementStatus.SUCCESS,
                                        1609,
                                        600L,
                                        null))
                                .toList());
                    })
                    .doFinally(ignored -> active.decrementAndGet());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
