package com.github.courtandrey.cinegraph.exporter;

import com.github.courtandrey.cinegraph.exporter.ingest.EdgeBuildService;
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

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 — scoring SQL correctness on a hand-crafted 8-movie fixture.
 * T6 — incremental edge maintenance touches only dirty movies' edges.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
class EdgeBuildServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("cinegraph")
            .withUsername("cinegraph")
            .withPassword("cinegraph")
            .withCommand("postgres", "-c", "synchronous_commit=off");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
        // dummy TMDB token — edge builds make no HTTP calls
        reg.add("tmdb.access-token", () -> "dummy");
    }

    @Autowired EdgeBuildService edgeBuildService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetFixture() {
        jdbc.execute("TRUNCATE movie, person, credit, genre, keyword, movie_genre, " +
                     "movie_keyword, edge, load_run, dirty_movie CASCADE");
        insertFixture();
    }

    // ── T5 ─────────────────────────────────────────────────────────────────────

    /**
     * M1–M2: same director P1 (DIRECTOR+DIRECTOR → 10×1.5=15), 1 genre (+2.0), same year (+4.0).
     * Expected total = 21.0.
     */
    @Test
    void sameDirector_scoreIs21() throws Exception {
        runFullRebuild();

        Map<String, Object> edge = findEdge(1, 2);
        assertThat(edge).isNotNull();
        assertThat(((Number) edge.get("crew_score")).floatValue()).isEqualTo(15.0f);
        assertThat(((Number) edge.get("total_score")).floatValue()).isEqualTo(21.0f);
    }

    /**
     * M3–M4: P2 is DIRECTOR on M3 and CAST_LEAD on M4 → min(10,5)=5 crew.
     * 3 shared genres → 6.0 (capped). Same year → 4.0. Expected total = 15.0.
     */
    @Test
    void directorVsLeadActor_scoreIs15() throws Exception {
        runFullRebuild();

        Map<String, Object> edge = findEdge(3, 4);
        assertThat(edge).isNotNull();
        assertThat(((Number) edge.get("crew_score")).floatValue()).isEqualTo(5.0f);
        assertThat(((Number) edge.get("total_score")).floatValue()).isEqualTo(15.0f);
    }

    /**
     * M7–M8: CREW_OTHER P8 (same role → 0.5×1.5=0.75 crew_score < min-crew-score 3.0).
     * 3 genres (+6) + 10 keywords (+5) + same year (+4) would give total 15.75, but
     * the pair is PRUNED in the edge_crew merge because crew_score < 3.
     */
    @Test
    void minCrewScoreExclusion_noEdge() throws Exception {
        runFullRebuild();

        Map<String, Object> edge = findEdge(7, 8);
        assertThat(edge).isNull();
    }

    /**
     * M9–M10: CAST_SUPPORT P9 (same role → 2.0×1.5=3.0 crew, meets min-crew-score).
     * 30-year gap → date_score = max(0, 4*(1-30/25)) = 0. Total = 3.0. With the persist
     * threshold removed, any edge clearing the crew guard is stored — so this edge exists.
     */
    @Test
    void noPersistThreshold_lowScoreEdgeStored() throws Exception {
        runFullRebuild();

        Map<String, Object> edge = findEdge(9, 10);
        assertThat(edge).isNotNull();
        assertThat(((Number) edge.get("crew_score")).floatValue()).isEqualTo(3.0f);
        assertThat(((Number) edge.get("total_score")).floatValue()).isEqualTo(3.0f);
    }

    /**
     * Component structure: M1–M2 edge components must include a SHARED_PERSON entry
     * with sameRole=true, score=15.0; a SHARED_GENRES entry; and a RELEASE_PROXIMITY entry.
     */
    @Test
    void components_containExpectedTypes() throws Exception {
        runFullRebuild();

        String components = jdbc.queryForObject(
                "SELECT components::text FROM edge WHERE movie_a=1 AND movie_b=2", String.class);
        assertThat(components).contains("SHARED_PERSON");
        assertThat(components).containsPattern("\"sameRole\":\\s*true");
        assertThat(components).contains("SHARED_GENRES");
        assertThat(components).contains("RELEASE_PROXIMITY");
    }

    // ── T6 ─────────────────────────────────────────────────────────────────────

    /**
     * After a full rebuild, modify M2's credits (add a new shared director with M1),
     * record M2 as dirty, run incremental edge maintenance, and assert:
     * - The M1–M2 edge was re-computed (higher score because of additional shared person)
     * - The M5–M6 edge was NOT touched (same computed_at)
     */
    @Test
    void incrementalEdges_onlyDirtyMovieEdgesChange() throws Exception {
        runFullRebuild();

        // Record computed_at for the M5–M6 edge before incremental
        java.time.Instant before56 = jdbc.queryForObject(
                "SELECT computed_at FROM edge WHERE movie_a=5 AND movie_b=6",
                java.time.Instant.class);
        float before12Total = jdbc.queryForObject(
                "SELECT total_score FROM edge WHERE movie_a=1 AND movie_b=2", Float.class);

        // Add a new person P100 (DIRECTOR) on both M1 and M2 to make the edge stronger
        jdbc.update("INSERT INTO person (person_id, name) VALUES (100, 'New Director')");
        jdbc.update("INSERT INTO credit (movie_id, person_id, role, department) VALUES (1, 100, 'DIRECTOR', 'Directing')");
        jdbc.update("INSERT INTO credit (movie_id, person_id, role, department) VALUES (2, 100, 'DIRECTOR', 'Directing')");

        // Create an ingest run and mark M2 (and M1 is affected since P100 is now on M1 too)
        long ingestRunId = jdbc.queryForObject(
                "INSERT INTO load_run (kind, status) VALUES ('INCREMENTAL', 'COMPLETED') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO dirty_movie (run_id, movie_id) VALUES (?, 2)", ingestRunId);
        jdbc.update("INSERT INTO dirty_movie (run_id, movie_id) VALUES (?, 1)", ingestRunId);

        // Run incremental edge maintenance
        long edgeRunId = edgeBuildService.triggerIncrementalEdges(ingestRunId);
        awaitRunCompletion(edgeRunId, 30, TimeUnit.SECONDS);

        // M1–M2 edge score should be higher (now 2 shared directors: P1 and P100)
        // 2 × 15 = 30 crew
        float after12Total = jdbc.queryForObject(
                "SELECT total_score FROM edge WHERE movie_a=1 AND movie_b=2", Float.class);
        assertThat(after12Total).isGreaterThan(before12Total);

        // M5–M6 edge must be unchanged (computed_at same as before)
        java.time.Instant after56 = jdbc.queryForObject(
                "SELECT computed_at FROM edge WHERE movie_a=5 AND movie_b=6",
                java.time.Instant.class);
        assertThat(after56).isEqualTo(before56);
    }

    // ── fixture ────────────────────────────────────────────────────────────────

    private void insertFixture() {
        // Genres
        insertGenre(28, "Action"); insertGenre(18, "Drama"); insertGenre(53, "Thriller");
        insertGenre(10, "Comedy"); insertGenre(11, "Horror"); insertGenre(12, "Romance");

        // Keywords (for M7/M8 min-crew-score test)
        for (int i = 1; i <= 10; i++) insertKeyword(i, "keyword" + i);

        // Persons
        insertPerson(1, "Director-P1");   // same director for M1+M2
        insertPerson(2, "Director-P2");   // director on M3, lead on M4
        for (int i = 3; i <= 7; i++) insertPerson(i, "Dir-" + i);  // 5 dirs for M5+M6 crew-cap test
        insertPerson(8, "Extra-P8");      // CREW_OTHER for M7+M8 min-crew-score test
        insertPerson(9, "Actor-P9");      // CAST_SUPPORT for M9+M10 threshold test

        // ── M1: year=2020, Genre=[28], Director=P1 ────────────────────────────
        insertMovie(1, "Movie 1", 2020);
        insertGenreLink(1, 28);
        insertCredit(1, 1, "DIRECTOR", "Directing", "Director");

        // ── M2: year=2020, Genre=[28], Director=P1 → same-director pair with M1 ─
        insertMovie(2, "Movie 2", 2020);
        insertGenreLink(2, 28);
        insertCredit(2, 1, "DIRECTOR", "Directing", "Director");

        // ── M3: year=2020, Genres=[28,18,53], Director=P2 ────────────────────
        insertMovie(3, "Movie 3", 2020);
        insertGenreLink(3, 28); insertGenreLink(3, 18); insertGenreLink(3, 53);
        insertCredit(3, 2, "DIRECTOR", "Directing", "Director");

        // ── M4: year=2020, Genres=[28,18,53], Lead=P2 → director↔lead-actor pair
        insertMovie(4, "Movie 4", 2020);
        insertGenreLink(4, 28); insertGenreLink(4, 18); insertGenreLink(4, 53);
        insertCastCredit(4, 2, "CAST_LEAD", 0, "Hero");

        // ── M5: 5 directors → crew-cap test ─────────────────────────────────
        insertMovie(5, "Movie 5", 2020);
        for (int i = 3; i <= 7; i++) insertCredit(5, i, "DIRECTOR", "Directing", "Director");

        // ── M6: same 5 directors → crew raw=75, capped at 60 ─────────────────
        insertMovie(6, "Movie 6", 2020);
        for (int i = 3; i <= 7; i++) insertCredit(6, i, "DIRECTOR", "Directing", "Director");

        // ── M7: CREW_OTHER=P8, 3 genres, 10 keywords → min-crew-score test ──
        insertMovie(7, "Movie 7", 2020);
        insertGenreLink(7, 10); insertGenreLink(7, 11); insertGenreLink(7, 12);
        for (int i = 1; i <= 10; i++) insertKeywordLink(7, i);
        insertCredit(7, 8, "CREW_OTHER", "Crew", "Grip");

        // ── M8: same as M7 ────────────────────────────────────────────────────
        insertMovie(8, "Movie 8", 2020);
        insertGenreLink(8, 10); insertGenreLink(8, 11); insertGenreLink(8, 12);
        for (int i = 1; i <= 10; i++) insertKeywordLink(8, i);
        insertCredit(8, 8, "CREW_OTHER", "Crew", "Grip");

        // ── M9: year=2020, CAST_SUPPORT=P9 → threshold test ─────────────────
        insertMovie(9, "Movie 9", 2020);
        insertCastCredit(9, 9, "CAST_SUPPORT", 5, "Character");

        // ── M10: year=1990 (30 years away from M9) → total<12 ───────────────
        insertMovie(10, "Movie 10", 1990);
        insertCastCredit(10, 9, "CAST_SUPPORT", 5, "Character");
    }

    // ── helper inserts ─────────────────────────────────────────────────────────

    private void insertGenre(int id, String name) {
        jdbc.update("INSERT INTO genre (genre_id, name) VALUES (?, ?)", id, name);
    }
    private void insertKeyword(int id, String name) {
        jdbc.update("INSERT INTO keyword (keyword_id, name) VALUES (?, ?)", id, name);
    }
    private void insertPerson(long id, String name) {
        jdbc.update("INSERT INTO person (person_id, name) VALUES (?, ?)", id, name);
    }
    private void insertMovie(long id, String title, int year) {
        jdbc.update("INSERT INTO movie (movie_id, title, release_year, countries) VALUES (?, ?, ?, '{}')",
                id, title, year);
    }
    private void insertGenreLink(long movieId, int genreId) {
        jdbc.update("INSERT INTO movie_genre (movie_id, genre_id) VALUES (?, ?)", movieId, genreId);
    }
    private void insertKeywordLink(long movieId, int keywordId) {
        jdbc.update("INSERT INTO movie_keyword (movie_id, keyword_id) VALUES (?, ?)", movieId, keywordId);
    }
    private void insertCredit(long movieId, long personId, String role, String dept, String job) {
        jdbc.update("INSERT INTO credit (movie_id, person_id, role, department, job) VALUES (?, ?, ?, ?, ?)",
                movieId, personId, role, dept, job);
    }
    private void insertCastCredit(long movieId, long personId, String role, int order, String character) {
        jdbc.update("INSERT INTO credit (movie_id, person_id, role, department, cast_order, character) " +
                    "VALUES (?, ?, ?, 'Acting', ?, ?)", movieId, personId, role, order, character);
    }

    // ── query helpers ──────────────────────────────────────────────────────────

    private Map<String, Object> findEdge(long a, long b) {
        long lo = Math.min(a, b), hi = Math.max(a, b);
        var rows = jdbc.queryForList(
                "SELECT movie_a, movie_b, crew_score, total_score FROM edge " +
                "WHERE movie_a = ? AND movie_b = ?", lo, hi);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void runFullRebuild() throws InterruptedException {
        long runId = edgeBuildService.triggerFullRebuild();
        awaitRunCompletion(runId, 60, TimeUnit.SECONDS);
    }

    private void awaitRunCompletion(long runId, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM load_run WHERE id = ?", String.class, runId);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                assertThat(status).as("run %d", runId).isEqualTo("COMPLETED");
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Run " + runId + " did not finish within timeout");
    }
}
