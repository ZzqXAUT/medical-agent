package com.example.mednav.cms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CmsMetastoreClientTest {

    @TempDir
    Path tempDirectory;

    @Test
    void resolvesDistributionAndReusesVerifiedRawFileOn304() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String base = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/items/27ea-46a8", exchange -> {
            byte[] body = ("{\"modified\":\"2026-09-01T00:00:00Z\","
                            + "\"released\":\"2026-09-02T00:00:00Z\","
                            + "\"distribution\":[{\"format\":\"CSV\","
                            + "\"downloadURL\":\"" + base + "/download.csv\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/download.csv", exchange -> {
            if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                byte[] body = "header\nvalue\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/csv");
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            CmsMetastoreClient client = new CmsMetastoreClient(
                    new ObjectMapper(),
                    URI.create(base + "/items"),
                    tempDirectory,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(3));
            CmsDownloadArtifact first =
                    client.resolveAndDownload(CmsDataset.FACILITY_AFFILIATION, null);
            assertThat(first.changed()).isTrue();
            assertThat(first.importFile().path()).exists();

            CmsImportFile file = first.importFile();
            CmsSourceFileRecord previous = new CmsSourceFileRecord(
                    "snapshot",
                    CmsDataset.FACILITY_AFFILIATION.datasetId(),
                    CmsDataset.FACILITY_AFFILIATION.logicalName(),
                    file.sourceModifiedAt(),
                    file.sourceReleasedAt(),
                    file.resolvedDownloadUrl(),
                    file.etag(),
                    file.expectedSha256(),
                    1,
                    file.path().toString());
            CmsDownloadArtifact second =
                    client.resolveAndDownload(CmsDataset.FACILITY_AFFILIATION, previous);

            assertThat(second.changed()).isFalse();
            assertThat(second.importFile().path()).isEqualTo(first.importFile().path());
        } finally {
            server.stop(0);
        }
    }
}
