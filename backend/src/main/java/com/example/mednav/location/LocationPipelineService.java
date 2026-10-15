package com.example.mednav.location;

import com.example.mednav.cms.CmsHospital;
import com.example.mednav.cms.CmsHospitalSearchResult;
import com.example.mednav.cms.CmsProviderQueryService;
import com.example.mednav.route.RouteMatrixService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class LocationPipelineService {

    private static final Logger log = LoggerFactory.getLogger(LocationPipelineService.class);

    private final GeocodingClient geocodingClient;
    private final CmsProviderQueryService cmsProviderQueryService;
    private final RouteMatrixService routeMatrixService;

    public LocationPipelineService(
            GeocodingClient geocodingClient,
            CmsProviderQueryService cmsProviderQueryService,
            RouteMatrixService routeMatrixService) {
        this.geocodingClient = geocodingClient;
        this.cmsProviderQueryService = cmsProviderQueryService;
        this.routeMatrixService = routeMatrixService;
    }

    public Mono<LocationRoutingResult> fromBrowser(GeoPoint point) {
        return route(geocodingClient.reverseGeocode(point));
    }

    public Mono<LocationRoutingResult> fromManualAddress(String address) {
        return route(geocodingClient.geocodeAddress(address));
    }

    private Mono<LocationRoutingResult> route(Mono<LocationResult> locationResult) {
        return locationResult.flatMap(location -> Mono.fromCallable(() ->
                        cmsProviderQueryService.hospitalSearchByCity(
                                location.state(), location.city()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(search -> routeHospitals(location, search)));
    }

    private Mono<LocationRoutingResult> routeHospitals(
            LocationResult location, CmsHospitalSearchResult search) {
        List<CmsHospital> hospitals = search.hospitals();
        log.info(
                "location_resolved source={} city={} state={} snapshotId={} hospitals={}",
                location.source(),
                location.city(),
                location.state(),
                search.snapshotId(),
                hospitals.size());
        if (search.snapshotId() == null) {
            return Mono.error(new LocationException(
                    "CMS_ACTIVE_SNAPSHOT_UNAVAILABLE",
                    "No ACTIVE CMS snapshot is available for hospital routing.",
                    false));
        }
        return routeMatrixService.compute(location.point(), hospitals, search.snapshotId())
                .map(routes -> new LocationRoutingResult(location, routes));
    }
}
