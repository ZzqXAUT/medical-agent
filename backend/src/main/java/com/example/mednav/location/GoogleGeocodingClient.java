package com.example.mednav.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@ConditionalOnProperty(name = "mednav.maps.mode", havingValue = "google")
public class GoogleGeocodingClient implements GeocodingClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final Duration requestTimeout;

    @Autowired
    public GoogleGeocodingClient(
            ObjectMapper objectMapper,
            @Value("${mednav.maps.geocoding-url:https://maps.googleapis.com/maps/api/geocode/json}")
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

    GoogleGeocodingClient(
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
    public Mono<LocationResult> reverseGeocode(GeoPoint point) {
        String query = "latlng=" + encode(point.latitude() + "," + point.longitude());
        return execute(query, LocationSource.BROWSER);
    }

    @Override
    public Mono<LocationResult> geocodeAddress(String address) {
        if (address == null || address.isBlank()) {
            return Mono.error(new LocationException(
                    "LOCATION_ADDRESS_REQUIRED", "A manual address or ZIP code is required.", true));
        }
        return execute("address=" + encode(address.strip()) + "&region=us", LocationSource.MANUAL);
    }

    private Mono<LocationResult> execute(String query, LocationSource source) {
        return Mono.fromCallable(() -> executeBlocking(query, source))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private LocationResult executeBlocking(String query, LocationSource source) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LocationException(
                    "MAPS_API_KEY_MISSING", "Google Maps API key is not configured.", false);
        }
        URI uri = URI.create(endpoint + "?" + query + "&key=" + encode(apiKey));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LocationException(
                        "GEOCODING_HTTP_" + response.statusCode(),
                        "Google Geocoding returned HTTP " + response.statusCode(),
                        false);
            }
            return parse(response.body(), source);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LocationException(
                    "GEOCODING_INTERRUPTED", "Google Geocoding was interrupted.", false, exception);
        } catch (IOException exception) {
            throw new LocationException(
                    "GEOCODING_IO", "Unable to call Google Geocoding.", false, exception);
        }
    }

    private LocationResult parse(String body, LocationSource source) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        String status = root.path("status").asText();
        if ("ZERO_RESULTS".equals(status)) {
            throw new LocationException(
                    "LOCATION_NOT_RESOLVED", "Google could not resolve this location.", true);
        }
        if (!"OK".equals(status) || !root.path("results").isArray() || root.path("results").isEmpty()) {
            throw new LocationException(
                    "GEOCODING_" + (status.isBlank() ? "INVALID_RESPONSE" : status),
                    "Google Geocoding did not return a usable result.",
                    false);
        }
        JsonNode result = root.path("results").get(0);
        JsonNode coordinates = result.path("geometry").path("location");
        GeoPoint point = new GeoPoint(coordinates.path("lat").asDouble(Double.NaN),
                coordinates.path("lng").asDouble(Double.NaN));
        String city = component(result, List.of("locality", "postal_town", "sublocality"), false);
        String state = component(result, List.of("administrative_area_level_1"), true);
        String postalCode = component(result, List.of("postal_code"), false);
        String country = component(result, List.of("country"), true);
        if (city == null || state == null || country == null) {
            throw new LocationException(
                    "LOCATION_COMPONENTS_INCOMPLETE",
                    "Resolved location must include city, state and country.",
                    true);
        }
        try {
            return new LocationResult(
                    point,
                    result.path("formatted_address").asText(),
                    city,
                    state,
                    postalCode,
                    country,
                    result.path("place_id").asText(null),
                    source,
                    Instant.now());
        } catch (IllegalArgumentException exception) {
            throw new LocationException(
                    "LOCATION_OUTSIDE_US", exception.getMessage(), true, exception);
        }
    }

    private String component(JsonNode result, List<String> preferredTypes, boolean shortName) {
        for (String preferredType : preferredTypes) {
            for (JsonNode component : result.path("address_components")) {
                for (JsonNode type : component.path("types")) {
                    if (preferredType.equals(type.asText())) {
                        String field = shortName ? "short_name" : "long_name";
                        String value = component.path(field).asText(null);
                        if (value != null && !value.isBlank()) {
                            return value;
                        }
                    }
                }
            }
        }
        return null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
