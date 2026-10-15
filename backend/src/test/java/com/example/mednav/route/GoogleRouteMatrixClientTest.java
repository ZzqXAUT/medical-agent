package com.example.mednav.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mednav.location.GeoPoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GoogleRouteMatrixClientTest {

    @Test
    void sendsFieldMaskAndMapsSuccessAndNoRouteElements() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/routes", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Goog-Api-Key"))
                    .isEqualTo("test-key");
            assertThat(exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"))
                    .contains("distanceMeters", "duration", "condition");
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] body = ("[{\"originIndex\":0,\"destinationIndex\":0,"
                            + "\"distanceMeters\":3218,\"duration\":\"600s\","
                            + "\"condition\":\"ROUTE_EXISTS\",\"status\":{}},"
                            + "{\"originIndex\":0,\"destinationIndex\":1,"
                            + "\"condition\":\"ROUTE_NOT_FOUND\",\"status\":{}}]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            GoogleRouteMatrixClient client = new GoogleRouteMatrixClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    "http://localhost:" + server.getAddress().getPort() + "/routes",
                    "test-key",
                    Duration.ofSeconds(2));
            List<RouteMatrixElement> result = client.compute(
                            new GeoPoint(31.2, -85.4),
                            List.of(
                                    new RouteDestination("010001", "100 Main St, Dothan, AL"),
                                    new RouteDestination("010002", "200 Main St, Dothan, AL")),
                            TravelMode.DRIVE)
                    .block();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).status()).isEqualTo(RouteElementStatus.SUCCESS);
            assertThat(result.get(0).distanceMeters()).isEqualTo(3218);
            assertThat(result.get(1).status()).isEqualTo(RouteElementStatus.NO_ROUTE);
            assertThat(requestBody.get().path("travelMode").asText()).isEqualTo("DRIVE");
            assertThat(requestBody.get().path("destinations")).hasSize(2);
        } finally {
            server.stop(0);
        }
    }
}
