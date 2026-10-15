package com.example.mednav.route;

import com.example.mednav.cms.CmsHospital;
import com.example.mednav.location.GeoPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class RouteMatrixService {

    private static final Logger log = LoggerFactory.getLogger(RouteMatrixService.class);
    private static final BigDecimal METERS_PER_MILE = new BigDecimal("1609.344");

    private final RouteMatrixClient client;
    private final RouteResultCache cache;
    private final int batchSize;
    private final int maxConcurrency;

    public RouteMatrixService(
            RouteMatrixClient client,
            RouteResultCache cache,
            @Value("${mednav.maps.route.batch-size:100}") int batchSize,
            @Value("${mednav.maps.route.max-concurrency:3}") int maxConcurrency) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Route batch size must be between 1 and 100");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("Route max concurrency must be positive");
        }
        this.client = client;
        this.cache = cache;
        this.batchSize = batchSize;
        this.maxConcurrency = maxConcurrency;
    }

    public Mono<RouteMatrixResult> compute(GeoPoint origin, List<CmsHospital> hospitals) {
        String snapshotId = hospitals.isEmpty() ? null : hospitals.get(0).snapshotId();
        return compute(origin, hospitals, snapshotId);
    }

    public Mono<RouteMatrixResult> compute(
            GeoPoint origin, List<CmsHospital> hospitals, String snapshotId) {
        Map<String, CmsHospital> uniqueHospitals = new LinkedHashMap<>();
        for (CmsHospital hospital : hospitals) {
            if (!snapshotId.equals(hospital.snapshotId())) {
                return Mono.error(new IllegalArgumentException(
                        "All hospitals must belong to the requested CMS snapshot"));
            }
            uniqueHospitals.putIfAbsent(hospital.facilityId(), hospital);
        }
        if (uniqueHospitals.isEmpty()) {
            return Mono.just(new RouteMatrixResult(snapshotId, 0, 0, 0, 0, 0, List.of()));
        }
        List<RouteMatrixElement> resolved = new ArrayList<>();
        List<RouteBatch> batches = new ArrayList<>();
        AtomicInteger cacheHits = new AtomicInteger();
        AtomicInteger cacheMisses = new AtomicInteger();

        for (TravelMode mode : TravelMode.values()) {
            List<RouteDestination> missing = new ArrayList<>();
            for (CmsHospital hospital : uniqueHospitals.values()) {
                cache.get(origin, snapshotId, hospital.facilityId(), mode).ifPresentOrElse(
                        element -> {
                            resolved.add(element);
                            cacheHits.incrementAndGet();
                        },
                        () -> {
                            missing.add(destination(hospital));
                            cacheMisses.incrementAndGet();
                        });
            }
            for (int start = 0; start < missing.size(); start += batchSize) {
                int end = Math.min(start + batchSize, missing.size());
                batches.add(new RouteBatch(mode, List.copyOf(missing.subList(start, end))));
            }
        }

        long started = System.nanoTime();
        return Flux.fromIterable(batches)
                .flatMap(
                        batch -> client.compute(origin, batch.destinations(), batch.mode())
                                .doOnNext(elements -> elements.forEach(
                                        element -> cache.put(origin, snapshotId, element)))
                                .onErrorResume(error -> {
                                    log.warn(
                                            "route_batch_failed snapshotId={} mode={} destinations={} errorType={}",
                                            snapshotId,
                                            batch.mode(),
                                            batch.destinations().size(),
                                            error.getClass().getSimpleName());
                                    return Mono.just(batch.destinations().stream()
                                            .map(destination -> new RouteMatrixElement(
                                                    destination.facilityId(),
                                                    batch.mode(),
                                                    RouteElementStatus.FAILED,
                                                    null,
                                                    null,
                                                    "ROUTE_BATCH_FAILED"))
                                            .toList());
                                }),
                        maxConcurrency)
                .flatMapIterable(elements -> elements)
                .collectList()
                .map(downloaded -> {
                    List<RouteMatrixElement> all = new ArrayList<>(resolved);
                    all.addAll(downloaded);
                    List<HospitalRoute> routes = assemble(snapshotId, uniqueHospitals, all);
                    log.info(
                            "route_matrix_completed snapshotId={} hospitals={} elements={} batches={} cacheHits={} cacheMisses={} latencyMs={}",
                            snapshotId,
                            uniqueHospitals.size(),
                            all.size(),
                            batches.size(),
                            cacheHits.get(),
                            cacheMisses.get(),
                            (System.nanoTime() - started) / 1_000_000);
                    return new RouteMatrixResult(
                            snapshotId,
                            uniqueHospitals.size(),
                            all.size(),
                            batches.size(),
                            cacheHits.get(),
                            cacheMisses.get(),
                            routes);
                });
    }

    private List<HospitalRoute> assemble(
            String snapshotId,
            Map<String, CmsHospital> hospitals,
            List<RouteMatrixElement> elements) {
        Map<String, EnumMap<TravelMode, RouteMatrixElement>> byHospital = new LinkedHashMap<>();
        for (String facilityId : hospitals.keySet()) {
            byHospital.put(facilityId, new EnumMap<>(TravelMode.class));
        }
        for (RouteMatrixElement element : elements) {
            EnumMap<TravelMode, RouteMatrixElement> modes = byHospital.get(element.facilityId());
            if (modes != null) {
                modes.put(element.mode(), element);
            }
        }
        Instant calculatedAt = Instant.now();
        return byHospital.entrySet().stream()
                .map(entry -> {
                    EnumMap<TravelMode, RouteMatrixElement> modes = entry.getValue();
                    RouteMatrixElement drive = modes.get(TravelMode.DRIVE);
                    RouteElementStatus status = drive == null
                            ? RouteElementStatus.FAILED
                            : drive.status();
                    return new HospitalRoute(
                            snapshotId,
                            entry.getKey(),
                            measure(drive),
                            measure(modes.get(TravelMode.TRANSIT)),
                            measure(modes.get(TravelMode.WALK)),
                            calculatedAt,
                            status);
                })
                .toList();
    }

    private RouteDestination destination(CmsHospital hospital) {
        String address = String.join(", ",
                hospital.address(),
                hospital.city(),
                hospital.state() + " " + hospital.zipCode());
        return new RouteDestination(hospital.facilityId(), address);
    }

    private RouteMeasure measure(RouteMatrixElement element) {
        if (element == null || element.status() != RouteElementStatus.SUCCESS) {
            return null;
        }
        BigDecimal miles = BigDecimal.valueOf(element.distanceMeters())
                .divide(METERS_PER_MILE, 3, RoundingMode.HALF_UP);
        long minutes = Math.max(1, (long) Math.ceil(element.durationSeconds() / 60.0));
        return new RouteMeasure(miles, minutes);
    }

    private record RouteBatch(TravelMode mode, List<RouteDestination> destinations) {}
}
