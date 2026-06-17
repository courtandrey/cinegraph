package com.github.courtandrey.cinegraph.api.repo;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.github.courtandrey.cinegraph.api.jooq.Tables.LETTERBOXD_SET;
import static org.jooq.impl.DSL.selectOne;

@Repository
public class LetterboxdSetRepository {

    public record MovieRating(long movieId, Double rating) {}

    private final DSLContext ctx;

    public LetterboxdSetRepository(DSLContext ctx) {
        this.ctx = ctx;
    }

    public boolean exists(String hash) {
        return ctx.fetchExists(selectOne().from(LETTERBOXD_SET).where(LETTERBOXD_SET.HASH.eq(hash)));
    }

    public List<Long> loadMovieIds(String hash) {
        return ctx.select(LETTERBOXD_SET.MOVIE_ID)
                .from(LETTERBOXD_SET)
                .where(LETTERBOXD_SET.HASH.eq(hash))
                .fetch(LETTERBOXD_SET.MOVIE_ID);
    }

    public void save(String hash, List<MovieRating> movies) {
        if (movies.isEmpty()) return;
        var insert = ctx.insertInto(LETTERBOXD_SET,
                        LETTERBOXD_SET.HASH, LETTERBOXD_SET.MOVIE_ID, LETTERBOXD_SET.RATING)
                .values(null, null, (Float) null)
                .onConflict(LETTERBOXD_SET.HASH, LETTERBOXD_SET.MOVIE_ID)
                .doNothing();
        var batch = ctx.batch(insert);
        movies.forEach(m -> batch.bind(hash, m.movieId(),
                m.rating() == null ? null : m.rating().floatValue()));
        batch.execute();
    }
}
