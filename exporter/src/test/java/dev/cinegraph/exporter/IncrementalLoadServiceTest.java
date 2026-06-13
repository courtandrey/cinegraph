package dev.cinegraph.exporter;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.cinegraph.exporter.ingest.IncrementalLoadService;
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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
class IncrementalLoadServiceTest {

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
        reg.add("tmdb.max-concurrency", () -> "4");
    }

    @AfterAll
    static void stopWireMock() { wm.stop(); }

    @Autowired IncrementalLoadService incrementalLoadService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        wm.resetAll();
        jdbc.execute("TRUNCATE fetch_queue, movie_raw, movie, genre, keyword, person, credit, "
                + "movie_genre, movie_keyword, dirty_movie, load_run, sync_state CASCADE");
        stubGenreList();
    }

    // ── T4 acceptance criterion 1: incremental updates movies and advances cursor ────────────

    @Test
    void incremental_updatesChangedMovies_andAdvancesCursor() throws Exception {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        setSyncState(yesterday);

        // Pre-insert movie 101 so we can verify it gets updated
        insertMinimalMovie(101, "Old Title");

        // TMDB reports movies 101 and 102 changed; 103 returns 404 (deleted)
        stubChangesApi(yesterday, List.of(101L, 102L, 103L));
        stubMovieBundle(101, "Updated Title 101");
        stubMovieBundle(102, "New Movie 102");
        stub404(103);

        // Also pre-insert movie 103 in DB so we can verify the deletion
        insertMinimalMovie(103, "To Be Deleted");

        long runId = incrementalLoadService.triggerIncremental();
        awaitCompletion(runId, 30, TimeUnit.SECONDS);

        // Movie 101 updated, movie 102 created
        assertThat(movieTitle(101)).isEqualTo("Updated Title 101");
        assertThat(movieTitle(102)).isEqualTo("New Movie 102");

        // Movie 103 deleted (404 from TMDB + deleteOnGone=true)
        Integer count103 = jdbc.queryForObject(
                "SELECT count(*) FROM movie WHERE movie_id = 103", Integer.class);
        assertThat(count103).isZero();

        // Cursor advanced to today exactly once
        String syncDate = jdbc.queryForObject(
                "SELECT value FROM sync_state WHERE key = 'last_change_sync_date'", String.class);
        assertThat(LocalDate.parse(syncDate)).isEqualTo(LocalDate.now(ZoneOffset.UTC));

        // load_run marked COMPLETED
        String status = jdbc.queryForObject(
                "SELECT status FROM load_run WHERE id = ?", String.class, runId);
        assertThat(status).isEqualTo("COMPLETED");
    }

    // ── T4 acceptance criterion 2: cursor advances exactly once ──────────────────────────────

    @Test
    void incremental_cursorAdvancesExactlyOnce() throws Exception {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        setSyncState(yesterday);

        stubChangesApi(yesterday, List.of(201L));
        stubMovieBundle(201, "Movie 201");

        long runId = incrementalLoadService.triggerIncremental();
        awaitCompletion(runId, 30, TimeUnit.SECONDS);

        String afterFirst = jdbc.queryForObject(
                "SELECT value FROM sync_state WHERE key = 'last_change_sync_date'", String.class);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(LocalDate.parse(afterFirst)).isEqualTo(today);

        // Run again: window is [today, today] — zero or one page of results
        stubChangesApiEmpty(today);
        long runId2 = incrementalLoadService.triggerIncremental();
        awaitCompletion(runId2, 30, TimeUnit.SECONDS);

        String afterSecond = jdbc.queryForObject(
                "SELECT value FROM sync_state WHERE key = 'last_change_sync_date'", String.class);
        assertThat(LocalDate.parse(afterSecond)).isEqualTo(today);

        // Exactly two INCREMENTAL runs total
        int runs = jdbc.queryForObject(
                "SELECT count(*) FROM load_run WHERE kind = 'INCREMENTAL'", Integer.class);
        assertThat(runs).isEqualTo(2);
    }

    // ── dirty_movie tracking ──────────────────────────────────────────────────────────────────

    @Test
    void incremental_recordsDirtyMovies() throws Exception {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        setSyncState(yesterday);

        stubChangesApi(yesterday, List.of(301L, 302L));
        stubMovieBundle(301, "Movie 301");
        stubMovieBundle(302, "Movie 302");

        long runId = incrementalLoadService.triggerIncremental();
        awaitCompletion(runId, 30, TimeUnit.SECONDS);

        // dirty_movie should contain entries for the two successfully upserted movies
        int dirtyCount = jdbc.queryForObject(
                "SELECT count(*) FROM dirty_movie WHERE run_id = ?", Integer.class, runId);
        assertThat(dirtyCount).isEqualTo(2);

        // The specific IDs
        var dirtyIds = jdbc.queryForList(
                "SELECT movie_id FROM dirty_movie WHERE run_id = ? ORDER BY movie_id",
                Long.class, runId);
        assertThat(dirtyIds).containsExactly(301L, 302L);
    }

    // ── no sync_state → FAILED ────────────────────────────────────────────────────────────────

    @Test
    void incremental_withoutSyncState_failsGracefully() throws Exception {
        // sync_state is empty; incremental has no start date
        assertThatThrownBy(() -> {
            long runId = incrementalLoadService.triggerIncremental();
            awaitCompletion(runId, 10, TimeUnit.SECONDS);
        }).isInstanceOf(AssertionError.class).hasMessageContaining("FAILED");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private void awaitCompletion(long runId, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM load_run WHERE id = ?", String.class, runId);
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                assertThat(status).as("run %d status", runId).isEqualTo("COMPLETED");
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Run " + runId + " did not finish within timeout");
    }

    private void setSyncState(LocalDate date) {
        jdbc.update("INSERT INTO sync_state (key, value) VALUES ('last_change_sync_date', ?)",
                date.toString());
    }

    private void insertMinimalMovie(long id, String title) {
        jdbc.update("INSERT INTO movie (movie_id, title, countries) VALUES (?, ?, '{}')", id, title);
        jdbc.update("INSERT INTO movie_raw (movie_id, payload) VALUES (?, '{}'::jsonb)", id);
    }

    private String movieTitle(long id) {
        return jdbc.queryForObject("SELECT title FROM movie WHERE movie_id = ?", String.class, id);
    }

    private void stubGenreList() {
        wm.stubFor(get(urlPathEqualTo("/3/genre/movie/list"))
                .willReturn(okJson("{\"genres\":[{\"id\":28,\"name\":\"Action\"}]}")));
    }

    private void stubChangesApi(LocalDate from, java.util.List<Long> ids) {
        StringBuilder results = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) results.append(",");
            results.append("{\"id\":").append(ids.get(i)).append(",\"adult\":false}");
        }
        results.append("]");
        wm.stubFor(get(urlPathEqualTo("/3/movie/changes"))
                .willReturn(okJson("{\"results\":" + results + ",\"page\":1,\"total_pages\":1,\"total_results\":" + ids.size() + "}")));
    }

    private void stubChangesApiEmpty(LocalDate date) {
        wm.stubFor(get(urlPathEqualTo("/3/movie/changes"))
                .willReturn(okJson("{\"results\":[],\"page\":1,\"total_pages\":1,\"total_results\":0}")));
    }

    private void stubMovieBundle(long id, String title) {
        String json = "{\"id\":" + id
                + ",\"title\":\"" + title + "\""
                + ",\"original_title\":\"" + title + "\""
                + ",\"release_date\":\"2021-06-01\""
                + ",\"original_language\":\"en\""
                + ",\"popularity\":5.0,\"vote_average\":7.5,\"vote_count\":500"
                + ",\"runtime\":90,\"overview\":\"Test.\",\"poster_path\":\"/p" + id + ".jpg\""
                + ",\"production_countries\":[{\"iso_3166_1\":\"US\"}]"
                + ",\"genres\":[{\"id\":28,\"name\":\"Action\"}]"
                + ",\"keywords\":{\"keywords\":[]}"
                + ",\"credits\":{\"cast\":[],\"crew\":[]}}";
        wm.stubFor(get(urlPathMatching("/3/movie/" + id + ".*"))
                .willReturn(okJson(json)));
    }

    private void stub404(long id) {
        wm.stubFor(get(urlPathMatching("/3/movie/" + id + ".*"))
                .willReturn(aResponse().withStatus(404).withBody("{\"status_code\":34}")));
    }
}
