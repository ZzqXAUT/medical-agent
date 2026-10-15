package com.example.mednav.route;

import com.example.mednav.location.GeoPoint;
import com.example.mednav.location.LocationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@ConditionalOnProperty(name = "mednav.maps.mode", havingValue = "google")
public class GoogleRouteMatrixClient implements RouteMatrixClient {

    private static final String FIELD_MASK =
            "originIndex,destinationIndex,duration,distanceMeters,status,condition";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final Duration requestTimeout;

    @Autowired
    public GoogleRouteMatrixClient(
            ObjectMapper objectMapper,
            @Value("${mednav.maps.routes-url:https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix}")
                    String endpoint,
            @Value("${mednav.maps.api-key:}") String apiKey,
            @Value("${mednav.maps.connect-timeout:10s}") Duration connectTimeout,
            @Value("${mednav.maps.request-timeout:30s}") Duration requestTimeout) {
        this(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build(),
                objectMapper,
                endpoint,
                apiKey,
                requestTimeout);
    }

    GoogleRouteMatrixClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String endpoint,
            String apiKey,
            Duration requestTimeout) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public Mono<List<RouteMatrixElement>> compute(
            GeoPoint origin, List<RouteDestination> destinations, TravelMode mode) {
        if (destinations.isEmpty()) {
            return Mono.just(List.of());
        }
        if (destinations.size() > 100) {
            return Mono.error(new IllegalArgumentException("Route batch cannot exceed 100 elements"));
        }
        return Mono.fromCallable(() -> executeBlocking(origin, destinations, mode))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<RouteMatrixElement> executeBlocking(
            GeoPoint origin, List<RouteDestination> destinations, TravelMode mode) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LocationException(
                    "MAPS_API_KEY_MISSING", "Google Maps API key is not configured.", false);
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(requestBody(origin, destinations, mode))))
                    .build();
        } catch (IOException exception) {
            throw new LocationException(
                    "ROUTE_REQUEST_INVALID", "Unable to serialize route request.", false, exception);
        }
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LocationException(
                        "ROUTES_HTTP_" + response.statusCode(),
                        "Google Routes returned HTTP " + response.statusCode(),
                        false);
            }
            return parse(response.body(), destinations, mode);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LocationException(
                    "ROUTES_INTERRUPTED", "Google Routes was interrupted.", false, exception);
        } catch (IOException exception) {
            throw new LocationException(
                    "ROUTES_IO", "Unable to call Google Routes.", false, exception);
        }
    }

    private ObjectNode requestBody(
            GeoPoint origin, List<RouteDestination> destinations, TravelMode mode) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode origins = body.putArray("origins");
        ObjectNode latLng = origins.addObject()
                .putObject("waypoint")
                .putObject("location")
                .putObject("latLng");
        latLng.put("latitude", origin.latitude());
        latLng.put("longitude", origin.longitude());
        ArrayNode destinationNodes = body.putArray("destinations");
        for (RouteDestination destination : destinations) {
            destinationNodes.addObject()
                    .putObject("waypoint")
                    .put("address", destination.address());
        }
        body.put("travelMode", mode.name());
        if (mode == TravelMode.DRIVE) {
            body.put("routingPreference", "TRAFFIC_AWARE");
        }
        body.put("languageCode", "en-US");
        body.put("regionCode", "US");
        body.put("units", "IMPERIAL");
        return body;
    }

    private List<RouteMatrixElement> parse(
            String body, List<RouteDestination> destinations, TravelMode mode) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        if (!root.isArray()) {
            throw new LocationException(
                    "ROUTES_INVALID_RESPONSE", "Google Routes response must be an array.", false);
        }
        Map<Integer, RouteMatrixElement> indexed = new HashMap<>();
        int responseOrdinal = 0;
        for (JsonNode node : root) {
            int index = node.has("destinationIndex")
                    ? node.path("destinationIndex").asInt(-1)
                    : responseOrdinal;
            responseOrdinal++;
            if (index < 0 || index >= destinations.size()) {
                continue;
            }
            RouteDestination destination = destinations.get(index);
            int statusCode = node.path("status").path("code").asInt(0);
            String condition = node.path("condition").asText("");
            if (statusCode != 0) {
                indexed.put(index, new RouteMatrixElement(
                        destination.facilityId(),
                        mode,
                        RouteElementStatus.FAILED,
                        null,
                        null,
                        "GOOGLE_STATUS_" + statusCode));
            } else if (!"ROUTE_EXISTS".equals(condition)) {
                indexed.put(index, new RouteMatrixElement(
                        destination.facilityId(),
                        mode,
                        RouteElementStatus.NO_ROUTE,
                        null,
                        null,
                        condition.isBlank() ? "NO_ROUTE" : condition));
            } else {
                indexed.put(index, new RouteMatrixElement(
                        destination.facilityId(),
                        mode,
                        RouteElementStatus.SUCCESS,
                        node.path("distanceMeters").asInt(),
                        parseDurationSeconds(node.path("duration").asText("0s")),
                        null));
            }
        }
        List<RouteMatrixElement> result = new ArrayList<>(destinations.size());
        for (int index = 0; index < destinations.size(); index++) {
            RouteDestination destination = destinations.get(index);
            result.add(indexed.getOrDefault(index, new RouteMatrixElement(
                    destination.facilityId(),
                    mode,
                    RouteElementStatus.FAILED,
                    null,
                    null,
                    "ROUTE_ELEMENT_MISSING")));
        }
        return List.copyOf(result);
    }

    private long parseDurationSeconds(String value) {
        if (value == null || !value.endsWith("s")) {
            throw new LocationException(
                    "ROUTES_DURATION_INVALID", "Google Routes returned an invalid duration.", false);
        }
        return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)));
    }
}
