package com.github.courtandrey.cinegraph.api.repo;

import com.github.courtandrey.cinegraph.api.dto.GraphNode;
import com.github.courtandrey.cinegraph.api.dto.LetterboxdSearchResult;
import com.github.courtandrey.cinegraph.api.dto.MovieDetail;
import com.github.courtandrey.cinegraph.api.dto.SearchResult;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.github.courtandrey.cinegraph.api.jooq.Tables.GENRE;
import static com.github.courtandrey.cinegraph.api.jooq.Tables.LETTERBOXD_SET;
import static com.github.courtandrey.cinegraph.api.jooq.Tables.MOVIE;
import static com.github.courtandrey.cinegraph.api.jooq.Tables.MOVIE_GENRE;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

@Repository
public class MovieQueryRepository {

    private static final int MAX_SEARCH = 50;
    private static final double FUZZY_THRESHOLD = 0.35;

    private final DSLContext ctx;

    public MovieQueryRepository(DSLContext ctx) {
        this.ctx = ctx;
    }

    public List<SearchResult> search(String q, int limit) {
        return search(q, limit, null);
    }

    public List<SearchResult> search(String q, int limit, Collection<Long> subset) {
        if (subset != null && subset.isEmpty()) return List.of();
        String tsQuery = Pg.tsQuery(q);
        if (tsQuery.isEmpty()) return List.of();
        int cap = Math.min(limit, MAX_SEARCH);

        List<SearchResult> primary =
                fetchMovies(ctx, Pg.ftsMatch(MOVIE.TITLE, MOVIE.ORIGINAL_TITLE, tsQuery), subset, cap);
        if (!primary.isEmpty()) return primary;

        return ctx.transactionResult(cfg -> {
            Pg.setWordSimilarityThreshold(cfg.dsl(), FUZZY_THRESHOLD);
            return fetchMovies(cfg.dsl(), fuzzyMatch(q), subset, cap);
        });
    }

    public List<LetterboxdSearchResult> searchInSet(String q, int limit, String hash) {
        String tsQuery = Pg.tsQuery(q);
        if (tsQuery.isEmpty()) return List.of();
        int cap = Math.min(limit, MAX_SEARCH);

        List<LetterboxdSearchResult> primary =
                fetchSetMovies(ctx, Pg.ftsMatch(MOVIE.TITLE, MOVIE.ORIGINAL_TITLE, tsQuery), hash, cap);
        if (!primary.isEmpty()) return primary;

        return ctx.transactionResult(cfg -> {
            Pg.setWordSimilarityThreshold(cfg.dsl(), FUZZY_THRESHOLD);
            return fetchSetMovies(cfg.dsl(), fuzzyMatch(q), hash, cap);
        });
    }

    private static Condition fuzzyMatch(String q) {
        return Pg.wordSimilarTo(q, MOVIE.TITLE).or(Pg.wordSimilarTo(q, MOVIE.ORIGINAL_TITLE));
    }

    private static List<SearchResult> fetchMovies(DSLContext dsl, Condition match,
                                                  Collection<Long> subset, int cap) {
        return dsl.select(MOVIE.MOVIE_ID, MOVIE.TITLE, MOVIE.RELEASE_YEAR, MOVIE.POSTER_PATH)
                .from(MOVIE)
                .where(subset == null ? match : match.and(MOVIE.MOVIE_ID.in(subset)))
                .orderBy(MOVIE.POPULARITY.desc().nullsLast())
                .limit(cap)
                .fetch(r -> new SearchResult(
                        r.value1(),
                        r.value2(),
                        toInteger(r.value3()),
                        r.value4()));
    }

    private static List<LetterboxdSearchResult> fetchSetMovies(DSLContext dsl, Condition match,
                                                               String hash, int cap) {
        return dsl.select(MOVIE.MOVIE_ID, MOVIE.TITLE, MOVIE.RELEASE_YEAR,
                        MOVIE.POSTER_PATH, LETTERBOXD_SET.GRAPH_ID)
                .from(MOVIE)
                .join(LETTERBOXD_SET).on(LETTERBOXD_SET.MOVIE_ID.eq(MOVIE.MOVIE_ID))
                .where(LETTERBOXD_SET.HASH.eq(hash).and(match))
                .orderBy(MOVIE.POPULARITY.desc().nullsLast())
                .limit(cap)
                .fetch(r -> new LetterboxdSearchResult(
                        r.value1(),
                        r.value2(),
                        toInteger(r.value3()),
                        r.value4(),
                        r.value5()));
    }

    public Optional<MovieDetail> findById(long id) {
        return ctx.select(
                        MOVIE.MOVIE_ID, MOVIE.TITLE, MOVIE.ORIGINAL_TITLE, MOVIE.RELEASE_YEAR,
                        MOVIE.RELEASE_DATE, MOVIE.OVERVIEW, MOVIE.RUNTIME, MOVIE.POSTER_PATH,
                        MOVIE.VOTE_AVERAGE, MOVIE.COUNTRIES,
                        multiset(
                                select(GENRE.GENRE_ID, GENRE.NAME)
                                        .from(MOVIE_GENRE)
                                        .join(GENRE).on(GENRE.GENRE_ID.eq(MOVIE_GENRE.GENRE_ID))
                                        .where(MOVIE_GENRE.MOVIE_ID.eq(MOVIE.MOVIE_ID))
                                        .orderBy(GENRE.GENRE_ID)
                        ).convertFrom(r -> r.map(g -> new MovieDetail.Genre(g.value1(), g.value2()))))
                .from(MOVIE)
                .where(MOVIE.MOVIE_ID.eq(id))
                .fetchOptional(r -> new MovieDetail(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        toInteger(r.value4()),
                        r.value5(),
                        r.value6(),
                        toInteger(r.value7()),
                        r.value8(),
                        toDouble(r.value9()),
                        r.value10() == null ? List.of() : List.of(r.value10()),
                        r.value11()));
    }

    public record TitleYearMatch(long movieId, String title, String originalTitle, int year) {}

    public List<TitleYearMatch> findByTitlesAndYears(Collection<String> lowerNames, Collection<Integer> years) {
        if (lowerNames.isEmpty() || years.isEmpty()) return List.of();
        List<Short> shortYears = years.stream().map(Integer::shortValue).toList();
        return ctx.select(MOVIE.MOVIE_ID, MOVIE.TITLE, MOVIE.ORIGINAL_TITLE, MOVIE.RELEASE_YEAR)
                .from(MOVIE)
                .where(MOVIE.RELEASE_YEAR.in(shortYears))
                .and(lower(MOVIE.TITLE).in(lowerNames)
                        .or(lower(MOVIE.ORIGINAL_TITLE).in(lowerNames)))
                .fetch(r -> new TitleYearMatch(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4() == null ? 0 : r.value4().intValue()));
    }

    public List<GraphNode> findNodesByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return ctx.select(MOVIE.MOVIE_ID, MOVIE.TITLE, MOVIE.RELEASE_YEAR, MOVIE.POSTER_PATH)
                .from(MOVIE)
                .where(MOVIE.MOVIE_ID.in(ids))
                .fetch(r -> new GraphNode(r.value1(), r.value2(), toInteger(r.value3()), r.value4(), 0.0));
    }

    private static Integer toInteger(Short s) {
        return s == null ? null : s.intValue();
    }

    private static Double toDouble(Float f) {
        return f == null ? null : f.doubleValue();
    }
}
