package com.example.mednav.location;

import com.example.mednav.route.RouteMatrixResult;

public record LocationRoutingResult(LocationResult location, RouteMatrixResult routeMatrix) {}
