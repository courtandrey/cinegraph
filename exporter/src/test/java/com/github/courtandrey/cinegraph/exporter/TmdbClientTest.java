package com.github.courtandrey.cinegraph.exporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.courtandrey.cinegraph.exporter.tmdb.TmdbClient;
import com.github.courtandrey.cinegraph.exporter.tmdb.TmdbClient.ExportNotFoundException;
import com.github.courtandrey.cinegraph.exporter.tmdb.TmdbClient.TmdbRateLimitException;
import com.github.courtandrey.cinegraph.exporter.tmdb.TmdbClient.TmdbServerException;
import com.github.courtandrey.cinegraph.exporter.tmdb.TmdbProperties;
import com.github.courtandrey.cinegraph.exporter.tmdb.dto.GenreListResponse;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class TmdbClientTest {

    static WireMockServer wm;
    TmdbClient client;

    @BeforeAll
    static void startWireMock() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stopWireMock() {
        wm.stop();
    }

    @BeforeEach
    void setup() {
        wm.resetAll();
        TmdbProperties props = new TmdbProperties();
        props.setBaseUrl("http://localhost:" + wm.port());
        props.setExportBaseUrl("http://localhost:" + wm.port());
        props.setAccessToken("test-token");

        RateLimiterConfig rlConfig = RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build();
        RateLimiterRegistry registry = RateLimiterRegistry.of(rlConfig);
        registry.rateLimiter("tmdb");

        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        client = new TmdbClient(http, props, new ObjectMapper(), registry);
    }

    @Test
    void getGenres_200_returnsGenres() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/3/genre/movie/list"))
                .willReturn(okJson("{\"genres\":[{\"id\":28,\"name\":\"Action\"}]}")));

        GenreListResponse result = client.getGenres();

        assertThat(result.genres()).hasSize(1);
        assertThat(result.genres().get(0).id()).isEqualTo(28);
        assertThat(result.genres().get(0).name()).isEqualTo("Action");
    }

    @Test
    void getMovieBundle_200_returnsRawJson() throws Exception {
        String json = "{\"id\":603,\"title\":\"The Matrix\",\"original_title\":\"The Matrix\","
                + "\"popularity\":84.5,\"vote_average\":8.2,\"vote_count\":23000,"
                + "\"genres\":[],\"credits\":{\"cast\":[],\"crew\":[]},\"keywords\":{\"keywords\":[]}"
                + ",\"production_countries\":[]}";
        wm.stubFor(get(urlPathEqualTo("/3/movie/603"))
                .willReturn(okJson(json)));

        Optional<String> result = client.getMovieBundle(603);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("\"id\":603");
    }

    @Test
    void getMovieBundle_404_returnsEmpty() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/3/movie/999999"))
                .willReturn(aResponse().withStatus(404).withBody("{\"status_code\":34}")));

        Optional<String> result = client.getMovieBundle(999999);

        assertThat(result).isEmpty();
    }

    @Test
    void getMovieBundle_429_throwsRateLimitException() {
        wm.stubFor(get(urlPathEqualTo("/3/movie/1"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "5")
                        .withBody("{\"status_message\":\"Too many requests\"}")));

        assertThatThrownBy(() -> client.getMovieBundle(1))
                .isInstanceOf(TmdbRateLimitException.class)
                .satisfies(ex -> assertThat(((TmdbRateLimitException) ex).getRetryAfterSeconds())
                        .isEqualTo(5));
    }

    @Test
    void getMovieBundle_500_throwsServerException() {
        wm.stubFor(get(urlPathEqualTo("/3/movie/2"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        assertThatThrownBy(() -> client.getMovieBundle(2))
                .isInstanceOf(TmdbServerException.class);
    }

    @Test
    void downloadIdExport_200_streamsLines() throws Exception {
        byte[] gz = gzip("{\"id\":1,\"adult\":false,\"video\":false,\"original_title\":\"Film A\",\"popularity\":1.0}\n"
                + "{\"id\":2,\"adult\":false,\"video\":false,\"original_title\":\"Film B\",\"popularity\":2.0}\n");
        LocalDate date = LocalDate.of(2026, 6, 10);
        wm.stubFor(get(urlPathEqualTo("/p/exports/movie_ids_06_10_2026.json.gz"))
                .willReturn(aResponse().withStatus(200).withBody(gz)
                        .withHeader("Content-Type", "application/gzip")));

        List<Long> ids;
        try (var stream = client.downloadIdExport(date)) {
            ids = stream.map(line -> line.id()).toList();
        }

        assertThat(ids).containsExactly(1L, 2L);
    }

    @Test
    void downloadIdExport_404_throwsExportNotFoundException() {
        LocalDate date = LocalDate.of(2026, 6, 10);
        wm.stubFor(get(urlPathEqualTo("/p/exports/movie_ids_06_10_2026.json.gz"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> {
            try (var stream = client.downloadIdExport(date)) {
                stream.count();
            }
        }).isInstanceOf(ExportNotFoundException.class);
    }

    private static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(content.getBytes());
        }
        return bos.toByteArray();
    }
}
