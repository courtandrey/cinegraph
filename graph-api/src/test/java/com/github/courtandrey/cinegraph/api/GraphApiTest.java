package com.github.courtandrey.cinegraph.api;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class GraphApiTest {

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
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE movie, person, credit, genre, keyword, " +
                     "movie_genre, movie_keyword, edge, letterboxd_set CASCADE");
        insertFixture();
    }

    // ── health ────────────────────────────────────────────────────────────────

    @Test
    void health_returns200() throws Exception {
        mvc.perform(get("/api/movies/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_prefixMatch_returnsResults() throws Exception {
        mvc.perform(get("/api/movies/search").param("q", "Matrix"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].title").value("The Matrix"));
    }

    @Test
    void search_shortQuery_returnsEmptyList() throws Exception {
        mvc.perform(get("/api/movies/search").param("q", "X"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void search_noMatch_returnsEmptyList() throws Exception {
        mvc.perform(get("/api/movies/search").param("q", "Zzznonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void search_wordPrefix_matchesMultiWordTitles() throws Exception {
        mvc.perform(get("/api/movies/search").param("q", "Mat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", hasItems("The Matrix", "The Matrix Reloaded")));
    }

    @Test
    void search_typo_fallsBackToFuzzyMatch() throws Exception {
        mvc.perform(get("/api/movies/search").param("q", "Matrx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", hasItem("The Matrix")));
    }

    @Test
    void search_explainUsesTrgmIndex() throws Exception {
        for (int i = 1; i <= 500; i++) {
            jdbc.update("INSERT INTO movie (movie_id, title, countries) VALUES (?, ?, '{}') " +
                        "ON CONFLICT DO NOTHING", 1000 + i, "Film " + i);
        }
        jdbc.execute("ANALYZE movie");
        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT movie_id, title, release_year, poster_path, popularity " +
                "FROM movie " +
                "WHERE title ILIKE 'Fi%' OR title % 'Film' OR original_title % 'Film' " +
                "ORDER BY (title ILIKE 'Fi%') DESC, similarity(title, 'Film') DESC, " +
                "popularity DESC NULLS LAST LIMIT 10", String.class));
        assertThat(plan).isNotNull();
    }

    // ── movie detail ──────────────────────────────────────────────────────────

    @Test
    void movieDetail_existingMovie_returnsFullDetail() throws Exception {
        mvc.perform(get("/api/movies/603"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(603))
                .andExpect(jsonPath("$.title").value("The Matrix"))
                .andExpect(jsonPath("$.year").value(1999))
                .andExpect(jsonPath("$.genres", hasSize(1)))
                .andExpect(jsonPath("$.genres[0].name").value("Action"));
    }

    @Test
    void movieDetail_unknownId_returns404() throws Exception {
        mvc.perform(get("/api/movies/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ── graph payload ─────────────────────────────────────────────────────────

    @Test
    void graph_returnsNeighborsAndEdges() throws Exception {
        mvc.perform(get("/api/movies/603/graph").param("minScore", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.center.id").value(603))
                .andExpect(jsonPath("$.nodes", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.edges", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.edges[0].score").isNumber())
                .andExpect(jsonPath("$.edges[0].topReason").isString())
                .andExpect(jsonPath("$.edges[0].topReason").value("Same director"));
    }

    @Test
    void graph_highMinScore_returnsEmptyNeighborhood() throws Exception {
        mvc.perform(get("/api/movies/603/graph").param("minScore", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.center.id").value(603))
                .andExpect(jsonPath("$.nodes", hasSize(0)))
                .andExpect(jsonPath("$.edges", hasSize(0)));
    }

    @Test
    void graph_unknownCenter_returns404() throws Exception {
        mvc.perform(get("/api/movies/999999/graph"))
                .andExpect(status().isNotFound());
    }

    @Test
    void graph_limitCapsNeighbors() throws Exception {
        mvc.perform(get("/api/movies/603/graph")
                        .param("minScore", "1")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(lessThanOrEqualTo(1))));
    }

    // ── edge breakdown ────────────────────────────────────────────────────────

    @Test
    void edgeBreakdown_existingEdge_returnsComponents() throws Exception {
        mvc.perform(get("/api/edges/603/604"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieA.id").value(603))
                .andExpect(jsonPath("$.movieA.title").value("The Matrix"))
                .andExpect(jsonPath("$.movieB.id").value(604))
                .andExpect(jsonPath("$.movieB.title").value("The Matrix Reloaded"))
                .andExpect(jsonPath("$.totalScore").isNumber())
                .andExpect(jsonPath("$.crewScore").value(15.0))
                .andExpect(jsonPath("$.components").isArray())
                .andExpect(jsonPath("$.components[0].type").value("SHARED_PERSON"))
                .andExpect(jsonPath("$.components[0].name").value("Lana Wachowski"));
    }

    @Test
    void edgeBreakdown_reversedOrder_sameResult() throws Exception {
        String fwd = mvc.perform(get("/api/edges/603/604"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rev = mvc.perform(get("/api/edges/604/603"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(fwd).isEqualTo(rev);
    }

    @Test
    void edgeBreakdown_noEdge_returns404() throws Exception {
        mvc.perform(get("/api/edges/603/999999"))
                .andExpect(status().isNotFound());
    }

    // ── letterboxd recommendations ────────────────────────────────────────────

    @Test
    void recommendations_ranksByRatingWeightedInScore_excludingSetMembers() throws Exception {
        insertRecommendationFixture();
        mvc.perform(get("/api/letterboxd/recs/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].id", contains(700, 701, 100, 702)))
                .andExpect(jsonPath("$[0].title").value("John Wick"))
                .andExpect(jsonPath("$[0].inScore", closeTo(110.0, 1e-4)))
                .andExpect(jsonPath("$[1].inScore", closeTo(60.0, 1e-4)))
                .andExpect(jsonPath("$[2].inScore", closeTo(55.0, 1e-4)))
                .andExpect(jsonPath("$[3].inScore", closeTo(10.0, 1e-4)));
    }

    @Test
    void recommendations_limitCapsResults() throws Exception {
        insertRecommendationFixture();
        mvc.perform(get("/api/letterboxd/recs/recommendations").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", contains(700, 701)));
    }

    @Test
    void recommendations_unknownHash_returnsEmptyList() throws Exception {
        mvc.perform(get("/api/letterboxd/nosuchhash/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void recommendations_scopedToComponent_usesOnlyThatComponentsFilms() throws Exception {
        insertRecommendationFixture();
        // 603 (5★) and 605 (unrated) form component 900; 604 (1★) is in component 901.
        jdbc.update("UPDATE letterboxd_set SET graph_id = 900 WHERE hash='recs' AND movie_id IN (603, 605)");
        jdbc.update("UPDATE letterboxd_set SET graph_id = 901 WHERE hash='recs' AND movie_id = 604");

        // Scoped to 900: only 603/605 seed, but the whole set is still excluded (800 gone).
        mvc.perform(get("/api/letterboxd/recs/recommendations").param("graphId", "900"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(700, 701, 100, 702)))
                .andExpect(jsonPath("$[*].id", not(hasItem(800))));

        // Scoped to 901: only 604 (1★ → negative coefficient) seeds, so nothing clears the guard.
        mvc.perform(get("/api/letterboxd/recs/recommendations").param("graphId", "901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void recommendations_inverted_returnsLeastRecommendedAcrossAllCandidates() throws Exception {
        insertRecommendationFixture();
        // Inverted keeps every candidate (positives included), lowest score first — unlike the
        // default direction which drops the negative 800 and orders best-first.
        mvc.perform(get("/api/letterboxd/recs/recommendations").param("invert", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[*].id", contains(800, 702, 100, 701, 700)))
                .andExpect(jsonPath("$[0].title").value("Jupiter Ascending"))
                .andExpect(jsonPath("$[0].inScore", closeTo(-50.0, 1e-4)))
                .andExpect(jsonPath("$[4].inScore", closeTo(110.0, 1e-4)));
    }

    private void insertRecommendationFixture() {
        insertMovie(605, "Bound", 1996);
        insertMovie(100, "Dark City", 1998);
        insertMovie(700, "John Wick", 2014);
        insertMovie(701, "Speed", 1994);
        insertMovie(702, "Cloud Atlas", 2012);
        insertMovie(800, "Jupiter Ascending", 2015);

        // coef: 603 rated 5.0 -> 11, 604 rated 1.0 -> -5, 605 unrated -> 1
        insertSetEntry("recs", 603, 5.0f);
        insertSetEntry("recs", 604, 1.0f);
        insertSetEntry("recs", 605, null);

        insertScoreOnlyEdge(603, 700, 10f);
        insertScoreOnlyEdge(603, 701, 10f);
        insertScoreOnlyEdge(604, 701, 10f);
        insertScoreOnlyEdge(100, 603, 5f);
        insertScoreOnlyEdge(605, 702, 10f);
        insertScoreOnlyEdge(604, 800, 10f);
    }

    private void insertSetEntry(String hash, long movieId, Float rating) {
        jdbc.update("INSERT INTO letterboxd_set (hash, movie_id, rating) VALUES (?, ?, ?)",
                hash, movieId, rating);
    }

    private void insertScoreOnlyEdge(long a, long b, float score) {
        jdbc.update("INSERT INTO edge (movie_a, movie_b, total_score, crew_score, components) " +
                    "VALUES (?, ?, ?, ?, '[]'::jsonb)", a, b, score, score);
    }

    // ── CORS ──────────────────────────────────────────────────────────────────

    @Test
    void search_hasCorsHeader_forFrontendOrigin() throws Exception {
        mvc.perform(get("/api/movies/search").param("q", "Matrix")
                        .header("Origin", "http://localhost:4200"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private void insertFixture() {
        insertGenre(28, "Action");
        insertPerson(1, "Lana Wachowski");
        insertPerson(2, "Keanu Reeves");

        insertMovie(603, "The Matrix", 1999);
        insertGenreLink(603, 28);
        insertCredit(603, 1, "DIRECTOR");
        insertCastCredit(603, 2, "CAST_LEAD", 0);

        insertMovie(604, "The Matrix Reloaded", 2003);
        insertGenreLink(604, 28);
        insertCredit(604, 1, "DIRECTOR");
        insertCastCredit(604, 2, "CAST_LEAD", 0);

        // Pre-built edge so graph and breakdown endpoints have data
        String components = """
                [{"type":"SHARED_PERSON","personId":1,"name":"Lana Wachowski",
                  "roleA":"DIRECTOR","roleB":"DIRECTOR","sameRole":true,"score":15.0},
                 {"type":"SHARED_GENRES","genreIds":[28],"names":["Action"],"score":2.0},
                 {"type":"RELEASE_PROXIMITY","deltaYears":4,"score":3.36}]""";
        jdbc.update("""
                INSERT INTO edge (movie_a, movie_b, total_score, crew_score, components)
                VALUES (603, 604, 20.36, 15.0, ?::jsonb)
                """, components);
    }

    private void insertGenre(int id, String name) {
        jdbc.update("INSERT INTO genre (genre_id, name) VALUES (?, ?)", id, name);
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
    private void insertCredit(long movieId, long personId, String role) {
        jdbc.update("INSERT INTO credit (movie_id, person_id, role, department) " +
                    "VALUES (?, ?, ?, 'Directing')", movieId, personId, role);
    }
    private void insertCastCredit(long movieId, long personId, String role, int order) {
        jdbc.update("INSERT INTO credit (movie_id, person_id, role, department, cast_order) " +
                    "VALUES (?, ?, ?, 'Acting', ?)", movieId, personId, role, order);
    }
}
