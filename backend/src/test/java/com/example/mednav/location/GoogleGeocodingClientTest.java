package com.example.mednav.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GoogleGeocodingClientTest {

    @Test
    void parsesUsCityStateAndCoordinatesForManualAndBrowserRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/geocode", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            assertThat(query).contains("key=test-key");
            byte[] body = response().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            GoogleGeocodingClient client = new GoogleGeocodingClient(
                    HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    "http://localhost:" + server.getAddress().getPort() + "/geocode",
                    "test-key",
                    Duration.ofSeconds(2));

            LocationResult manual = client.geocodeAddress("Dothan, AL 36301").block();
            LocationResult browser = client.reverseGeocode(new GeoPoint(31.2, -85.4)).block();

            assertThat(manual).isNotNull();
            assertThat(manual.city()).isEqualTo("Dothan");
            assertThat(manual.state()).isEqualTo("AL");
            assertThat(manual.countryCode()).isEqualTo("US");
            assertThat(manual.source()).isEqualTo(LocationSource.MANUAL);
            assertThat(browser).isNotNull();
            assertThat(browser.source()).isEqualTo(LocationSource.BROWSER);
        } finally {
            server.stop(0);
        }
    }

    private String response() {
        return """
                {
                  "status":"OK",
                  "results":[{
                    "formatted_address":"1108 Ross Clark Circle, Dothan, AL 36301, USA",
                    "place_id":"place-1",
                    "geometry":{"location":{"lat":31.2147,"lng":-85.36137}},
                    "address_components":[
                      {"long_name":"Dothan","short_name":"Dothan","types":["locality","political"]},
                      {"long_name":"Alabama","short_name":"AL","types":["administrative_area_level_1","political"]},
                      {"long_name":"36301","short_name":"36301","types":["postal_code"]},
                      {"long_name":"United States","short_name":"US","types":["country","political"]}
                    ]
                  }]
                }
                """;
    }
}
