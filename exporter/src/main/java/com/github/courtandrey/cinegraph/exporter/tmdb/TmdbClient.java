package com.github.courtandrey.cinegraph.exporter.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.courtandrey.cinegraph.exporter.tmdb.dto.ChangesPage;
import com.github.courtandrey.cinegraph.exporter.tmdb.dto.GenreListResponse;
import com.github.courtandrey.cinegraph.exporter.tmdb.dto.IdLine;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

@Component
public class TmdbClient {

    private static final Logger log = LoggerFactory.getLogger(TmdbClient.class);
    private static final DateTimeFormatter EXPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("MM_dd_yyyy");
    private static final String APPEND = "credits,keywords,release_dates";

    private final HttpClient http;
    private final TmdbProperties props;
    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;

    public TmdbClient(HttpClient http, TmdbProperties props, ObjectMapper mapper,
                      RateLimiterRegistry rateLimiterRegistry) {
        this.http = http;
        this.props = props;
        this.mapper = mapper;
        this.rateLimiter = rateLimiterRegistry.rateLimiter("tmdb");
    }

    public GenreListResponse getGenres() throws IOException, InterruptedException {
        String body = get(props.getBaseUrl() + "/3/genre/movie/list?language=en-US");
        return mapper.readValue(body, GenreListResponse.class);
    }

    /**
     * Fetches full movie details with credits/keywords appended.
     * Returns empty on 404 (TMDB-deleted movie).
     * Throws {@link TmdbRateLimitException} on 429.
     * Throws {@link TmdbServerException} on 5xx.
     */
    public Optional<String> getMovieBundle(long movieId) throws IOException, InterruptedException {
        String url = props.getBaseUrl() + "/3/movie/" + movieId
                + "?append_to_response=" + APPEND + "&language=en-US";
        return getWithNotFound(url);
    }

    public ChangesPage getChanges(LocalDate startDate, LocalDate endDate, int page)
            throws IOException, InterruptedException {
        String url = props.getBaseUrl() + "/3/movie/changes?start_date=" + startDate
                + "&end_date=" + endDate + "&page=" + page;
        String body = get(url);
        return mapper.readValue(body, ChangesPage.class);
    }

    /**
     * Streams newline-delimited JSON lines from the daily ID export .json.gz file.
     * Caller must close the returned stream to release the HTTP connection.
     */
    public Stream<IdLine> downloadIdExport(LocalDate date) throws IOException, InterruptedException {
        String url = props.getExportBaseUrl() + "/p/exports/movie_ids_"
                + EXPORT_DATE_FORMAT.format(date) + ".json.gz";

        acquireRateLimitPermission();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + props.getAccessToken())
                .GET()
                .build();

        HttpResponse<java.io.InputStream> response = http.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() == 403 || response.statusCode() == 404) {
            response.body().close();
            throw new ExportNotFoundException("Export not available for " + date + ": HTTP " + response.statusCode());
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new TmdbServerException("Export download failed: HTTP " + response.statusCode());
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(response.body())));
        return reader.lines()
                .onClose(() -> {
                    try { reader.close(); } catch (IOException ignored) {}
                })
                .map(line -> {
                    try {
                        return mapper.readValue(line, IdLine.class);
                    } catch (Exception e) {
                        log.warn("Skipping malformed export line: {}", line);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull);
    }

    private String get(String url) throws IOException, InterruptedException {
        acquireRateLimitPermission();
        HttpResponse<String> response = http.send(
                buildRequest(url), HttpResponse.BodyHandlers.ofString());
        return handleResponse(url, response);
    }

    private Optional<String> getWithNotFound(String url) throws IOException, InterruptedException {
        acquireRateLimitPermission();
        HttpResponse<String> response = http.send(
                buildRequest(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return Optional.empty();
        return Optional.of(handleResponse(url, response));
    }

    private void acquireRateLimitPermission() {
        if (!rateLimiter.acquirePermission()) {
            throw new TmdbRateLimitException(1);
        }
    }

    private HttpRequest buildRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + props.getAccessToken())
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private String handleResponse(String url, HttpResponse<String> response)
            throws TmdbRateLimitException, TmdbServerException {
        return switch (response.statusCode() / 100) {
            case 2 -> response.body();
            case 4 -> {
                if (response.statusCode() == 429) {
                    int retryAfter = parseRetryAfter(response, 2);
                    throw new TmdbRateLimitException(retryAfter);
                }
                throw new TmdbClientException("Client error " + response.statusCode() + " for " + url);
            }
            default -> throw new TmdbServerException("Server error " + response.statusCode() + " for " + url);
        };
    }

    private int parseRetryAfter(HttpResponse<?> response, int defaultSeconds) {
        return response.headers().firstValue("Retry-After")
                .map(v -> {
                    try { return Integer.parseInt(v.trim()); }
                    catch (NumberFormatException e) { return defaultSeconds; }
                })
                .orElse(defaultSeconds);
    }


    public static class TmdbRateLimitException extends RuntimeException {
        private final int retryAfterSeconds;
        public TmdbRateLimitException(int retryAfterSeconds) {
            super("Rate limited; retry after " + retryAfterSeconds + "s");
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public int getRetryAfterSeconds() { return retryAfterSeconds; }
    }

    public static class TmdbServerException extends RuntimeException {
        public TmdbServerException(String msg) { super(msg); }
    }

    public static class TmdbClientException extends RuntimeException {
        public TmdbClientException(String msg) { super(msg); }
    }

    public static class ExportNotFoundException extends RuntimeException {
        public ExportNotFoundException(String msg) { super(msg); }
    }
}
