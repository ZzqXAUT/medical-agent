package com.example.mednav.route;

import java.util.List;

public record RouteMatrixResult(
        String snapshotId,
        int hospitalCount,
        int routeElementCount,
        int batchCount,
        int cacheHits,
        int cacheMisses,
        List<HospitalRoute> routes) {

    public RouteMatrixResult {
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
