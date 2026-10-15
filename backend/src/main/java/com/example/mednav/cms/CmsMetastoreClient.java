package com.example.mednav.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Resolves stable CMS dataset IDs and conditionally downloads immutable raw CSV objects. */
@Component
public class CmsMetastoreClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI metastoreBaseUri;
    private final Path rawRoot;
    private final Duration requestTimeout;

    public CmsMetastoreClient(
            ObjectMapper objectMapper,
            @Value("${mednav.cms.metastore-base-url}") URI metastoreBaseUri,
            @Value("${mednav.cms.data-dir:../dataset}") Path dataDir,
            @Value("${mednav.cms.connect-timeout:10s}") Duration connectTimeout,
            @Value("${mednav.cms.request-timeout:2m}") Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.metastoreBaseUri = metastoreBaseUri;
        this.rawRoot = dataDir.toAbsolutePath().normalize().resolve("cms-runtime").resolve("raw");
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CmsDownloadArtifact resolveAndDownload(
            CmsDataset dataset, CmsSourceFileRecord previous) {
        DatasetMetadata metadata = fetchMetadata(dataset);
        HttpRequest.Builder request = HttpRequest.newBuilder(metadata.downloadUri())
                .timeout(requestTimeout)
                .header("Accept", "text/csv,application/octet-stream;q=0.9")
                .GET();
        if (previous != null && StringUtils.hasText(previous.etag())) {
            request.header("If-None-Match", previous.etag());
        }
        if (previous != null && previous.sourceModifiedAt() != null) {
            request.header(
                    "If-Modified-Since",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(
                            previous.sourceModifiedAt().atZone(java.time.ZoneOffset.UTC)));
        }

        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 304) {
                if (previous == null || !Files.isRegularFile(Path.of(previous.rawObjectKey()))) {
                    throw new CmsImportException(
                            "CMS_304_WITHOUT_RAW_FILE",
                            "CMS returned 304 but no verified raw file is available");
                }
                return new CmsDownloadArtifact(
                        new CmsImportFile(
                                dataset,
                                Path.of(previous.rawObjectKey()),
                                metadata.downloadUri().toString(),
                                previous.etag(),
                                metadata.modifiedAt(),
                                metadata.releasedAt(),
                                previous.sha256()),
                        false);
            }
            if (response.statusCode() != 200) {
                throw new CmsImportException(
                        "CMS_DOWNLOAD_HTTP_" + response.statusCode(),
                        "CMS download failed for " + dataset.logicalName());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.isBlank()
                    && !contentType.toLowerCase(java.util.Locale.ROOT).contains("csv")
                    && !contentType.toLowerCase(java.util.Locale.ROOT).contains("octet-stream")) {
                throw new CmsImportException(
                        "CMS_CONTENT_TYPE_INVALID", "Unexpected CMS content type: " + contentType);
            }

            DownloadedObject downloaded = store(dataset, response.body());
            boolean changed = previous == null
                    || !downloaded.sha256().equalsIgnoreCase(previous.sha256());
            String etag = response.headers().firstValue("ETag").orElse(metadata.etag());
            return new CmsDownloadArtifact(
                    new CmsImportFile(
                            dataset,
                            downloaded.path(),
                            metadata.downloadUri().toString(),
                            etag,
                            metadata.modifiedAt(),
                            metadata.releasedAt(),
                            downloaded.sha256()),
                    changed);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CmsImportException(
                    "CMS_DOWNLOAD_INTERRUPTED", "CMS download was interrupted", exception);
        } catch (IOException exception) {
            throw new CmsImportException(
                    "CMS_DOWNLOAD_IO", "Unable to download " + dataset.logicalName(), exception);
        }
    }

    private DatasetMetadata fetchMetadata(CmsDataset dataset) {
        URI uri = URI.create(metastoreBaseUri.toString().replaceAll("/$", "")
                + "/" + dataset.datasetId());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new CmsImportException(
                        "CMS_METADATA_HTTP_" + response.statusCode(),
                        "CMS metadata lookup failed for " + dataset.datasetId());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode distributions = root.path("distribution");
            if (!distributions.isArray()) {
                distributions = root.path("distributions");
            }
            String downloadUrl = null;
            if (distributions.isArray()) {
                for (JsonNode distribution : distributions) {
                    String candidate = text(distribution, "downloadURL", "downloadUrl", "accessURL");
                    String format = text(distribution, "format", "mediaType");
                    if (candidate != null
                            && (downloadUrl == null
                                    || (format != null && format.toLowerCase(java.util.Locale.ROOT)
                                            .contains("csv")))) {
                        downloadUrl = candidate;
                        if (format != null && format.toLowerCase(java.util.Locale.ROOT).contains("csv")) {
                            break;
                        }
                    }
                }
            }
            if (!StringUtils.hasText(downloadUrl)) {
                throw new CmsImportException(
                        "CMS_DOWNLOAD_URL_MISSING",
                        "CMS metadata contains no CSV distribution for " + dataset.datasetId());
            }
            return new DatasetMetadata(
                    URI.create(downloadUrl),
                    instant(text(root, "modified")),
                    instant(text(root, "released", "issued")),
                    null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CmsImportException(
                    "CMS_METADATA_INTERRUPTED", "CMS metadata lookup was interrupted", exception);
        } catch (IOException exception) {
            throw new CmsImportException(
                    "CMS_METADATA_IO", "Unable to read CMS metadata", exception);
        }
    }

    private DownloadedObject store(CmsDataset dataset, InputStream responseBody) throws IOException {
        Path datasetDirectory = rawRoot.resolve(dataset.logicalName()).normalize();
        if (!datasetDirectory.startsWith(rawRoot)) {
            throw new CmsImportException("CMS_PATH_INVALID", "Resolved CMS path escaped data directory");
        }
        Files.createDirectories(datasetDirectory);
        Path temporary = Files.createTempFile(datasetDirectory, "download-", ".part");
        MessageDigest digest = sha256Digest();
        long bytes;
        try (InputStream input = new DigestInputStream(responseBody, digest)) {
            bytes = Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        } catch (RuntimeException | IOException error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
        if (bytes == 0) {
            Files.deleteIfExists(temporary);
            throw new CmsImportException("CMS_EMPTY_DOWNLOAD", "CMS download contained zero bytes");
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        Path target = datasetDirectory.resolve(sha256 + ".csv");
        if (Files.exists(target)) {
            Files.deleteIfExists(temporary);
        } else {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
        }
        return new DownloadedObject(target, sha256, bytes);
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && !value.textValue().isBlank()) {
                return value.textValue();
            }
        }
        return null;
    }

    private Instant instant(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException invalid) {
                throw new CmsImportException("CMS_METADATA_DATE_INVALID", "Invalid CMS metadata date");
            }
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record DatasetMetadata(
            URI downloadUri, Instant modifiedAt, Instant releasedAt, String etag) {}

    private record DownloadedObject(Path path, String sha256, long bytes) {}

}
