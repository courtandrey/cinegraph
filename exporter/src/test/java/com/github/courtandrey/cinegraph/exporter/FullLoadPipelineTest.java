package com.github.courtandrey.cinegraph.exporter;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.courtandrey.cinegraph.exporter.ingest.FullLoadService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
class FullLoadPipelineTest {

    private static final int MOVIE_COUNT = 500;
    private static final DateTimeFormatter EXPORT_FMT = DateTimeFormatter.ofPattern("MM_dd_yyyy");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("cinegraph")
            .withUsername("cinegraph")
            .withPassword("cinegraph")
            .withCommand("postgres", "-c", "synchronous_commit=off");

    static WireMockServer wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry reg) {
        wm.start();
        reg.add("spring.datasource.url", postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
        reg.add("tmdb.base-url", () -> "http://localhost:" + wm.port());
        reg.add("tmdb.export-base-url", () -> "http://localhost:" + wm.port());
        reg.add("tmdb.access-token", () -> "test-token");
        reg.add("tmdb.max-concurrency", () -> "8");
    }

    @AfterAll
    static void stopWireMock() {
        wm.stop();
    }

    @Autowired FullLoadService fullLoadService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetDb() {
        wm.resetAll();
        jdbc.execute("TRUNCATE fetch_queue, movie_raw, movie, genre, keyword, person, credit, "
                + "movie_genre, movie_keyword, load_run, sync_state CASCADE");
        stubWireMock();
    }

    @Test
    void fullLoad_500fakeMovies_completes() throws Exception {
        long runId = fullLoadService.triggerFullLoad();

        awaitCompletion(runId, 120, TimeUnit.SECONDS);

        // All 500 movies in DB
        int movieCount = jdbc.queryForObject("SELECT count(*) FROM movie", Integer.class);
        assertThat(movieCount).isEqualTo(MOVIE_COUNT);

        // Queue all DONE or GONE
        int pending = jdbc.queryForObject(
                "SELECT count(*) FROM fetch_queue WHERE state NOT IN ('DONE','GONE')", Integer.class);
        assertThat(pending).isZero();

        // sync_state set
        String syncDate = jdbc.queryForObject(
                "SELECT value FROM sync_state WHERE key = 'last_change_sync_date'", String.class);
        assertThat(syncDate).isNotBlank();

        // Load run marked COMPLETED
        String status = jdbc.queryForObject(
                "SELECT status FROM load_run WHERE id = ?", String.class, runId);
        assertThat(status).isEqualTo("COMPLETED");
    }

    @Test
    void fullLoad_retriggered_isIdempotent() throws Exception {
        long runId = fullLoadService.triggerFullLoad();
        awaitCompletion(runId, 120, TimeUnit.SECONDS);

        // Re-trigger: should resume (all DONE, so pipeline exits immediately with no new fetches)
        wm.resetAll();
        stubWireMock();
        long runId2 = fullLoadService.triggerFullLoad();
        awaitCompletion(runId2, 30, TimeUnit.SECONDS);

        int movieCount = jdbc.queryForObject("SELECT count(*) FROM movie", Integer.class);
        assertThat(movieCount).isEqualTo(MOVIE_COUNT);

        // No duplicate rows in movie_genre
        int dupCheck = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT movie_id, genre_id, count(*) c "
                        + "FROM movie_genre GROUP BY movie_id, genre_id HAVING count(*) > 1) t",
                Integer.class);
        assertThat(dupCheck).isZero();
    }

    @Test
    void fullLoad_withPartialQueue_resumesFromPending() throws Exception {
        // Pre-seed 300 movies as DONE and 200 as PENDING to simulate a resumed run
        for (int i = 1; i <= 300; i++) {
            jdbc.update("INSERT INTO fetch_queue (movie_id, state) VALUES (?, 'DONE')", (long) i);
            // Also insert minimal movie rows so we don't violate counts
            jdbc.update("INSERT INTO movie (movie_id, title, countries) VALUES (?, ?, '{}')",
                    (long) i, "Movie " + i);
        }
        for (int i = 301; i <= MOVIE_COUNT; i++) {
            jdbc.update("INSERT INTO fetch_queue (movie_id) VALUES (?)", (long) i);
        }

        long runId = fullLoadService.triggerFullLoad();
        awaitCompletion(runId, 60, TimeUnit.SECONDS);

        // All 500 should now be present
        int movieCount = jdbc.queryForObject("SELECT count(*) FROM movie", Integer.class);
        assertThat(movieCount).isEqualTo(MOVIE_COUNT);
    }

    // ---- helpers ------------------------------------------------------------

    private void awaitCompletion(long runId, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM load_run WHERE id = ?", String.class, runId);
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                assertThat(status).isEqualTo("COMPLETED");
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Run " + runId + " did not complete within " + timeout + " " + unit);
    }

    private void stubWireMock() {
        // Genre list
        wm.stubFor(get(urlPathEqualTo("/3/genre/movie/list"))
                .willReturn(okJson("{\"genres\":[{\"id\":28,\"name\":\"Action\"},{\"id\":18,\"name\":\"Drama\"}]}")));

        // ID export: try today first, then fall back
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = 0; i <= 3; i++) {
            LocalDate d = today.minusDays(i);
            String path = "/p/exports/movie_ids_" + EXPORT_FMT.format(d) + ".json.gz";
            if (i == 0) {
                try {
                    wm.stubFor(get(urlPathEqualTo(path))
                            .willReturn(aResponse().withStatus(200)
                                    .withBody(buildExportGz())
                                    .withHeader("Content-Type", "application/gzip")));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                wm.stubFor(get(urlPathEqualTo(path))
                        .willReturn(aResponse().withStatus(404)));
            }
        }

        // Movie bundles: one per movie ID 1..500
        for (int id = 1; id <= MOVIE_COUNT; id++) {
            String json = buildMovieJson(id);
            wm.stubFor(get(urlPathMatching("/3/movie/" + id + ".*"))
                    .willReturn(okJson(json)));
        }
    }

    private static byte[] buildExportGz() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= MOVIE_COUNT; i++) {
            sb.append("{\"id\":").append(i)
                    .append(",\"adult\":false,\"video\":false,\"original_title\":\"Movie ")
                    .append(i).append("\",\"popularity\":").append(i).append("}\n");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(sb.toString().getBytes());
        }
        return bos.toByteArray();
    }

    private static String buildMovieJson(int id) {
        return "{\"id\":" + id
                + ",\"title\":\"Movie " + id + "\""
                + ",\"original_title\":\"Movie " + id + "\""
                + ",\"release_date\":\"2020-01-01\""
                + ",\"original_language\":\"en\""
                + ",\"popularity\":" + id
                + ",\"vote_average\":7.0,\"vote_count\":1000"
                + ",\"runtime\":100"
                + ",\"overview\":\"A movie.\""
                + ",\"poster_path\":\"/poster" + id + ".jpg\""
                + ",\"production_countries\":[{\"iso_3166_1\":\"US\"}]"
                + ",\"genres\":[{\"id\":28,\"name\":\"Action\"}]"
                + ",\"keywords\":{\"keywords\":[{\"id\":1,\"name\":\"action\"}]}"
                + ",\"credits\":{"
                + "\"cast\":[{\"id\":1000,\"name\":\"Actor One\",\"order\":0,\"character\":\"Hero\"}]"
                + ",\"crew\":[{\"id\":2000,\"name\":\"Director One\",\"department\":\"Directing\",\"job\":\"Director\"}]"
                + "}}";
    }
}
