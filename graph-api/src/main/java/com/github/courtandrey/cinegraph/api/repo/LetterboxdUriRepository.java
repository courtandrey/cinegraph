package com.github.courtandrey.cinegraph.api.repo;

import com.github.courtandrey.cinegraph.api.domain.LetterboxdUriOutcome;
import com.github.courtandrey.cinegraph.api.domain.UriResolution;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.github.courtandrey.cinegraph.api.jooq.Tables.LETTERBOXD_URI;
import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.val;

@Repository
public class LetterboxdUriRepository {

    private final DSLContext ctx;

    public LetterboxdUriRepository(DSLContext ctx) {
        this.ctx = ctx;
    }

    public Optional<UriResolution> find(String uri) {
        return ctx.select(LETTERBOXD_URI.OUTCOME, LETTERBOXD_URI.MOVIE_ID)
                .from(LETTERBOXD_URI)
                .where(LETTERBOXD_URI.URI.eq(uri))
                .fetchOptional(r -> new UriResolution(
                        LetterboxdUriOutcome.valueOf(r.value1()), r.value2()));
    }

    public void save(String uri, UriResolution resolution) {
        ctx.insertInto(LETTERBOXD_URI,
                        LETTERBOXD_URI.URI, LETTERBOXD_URI.OUTCOME,
                        LETTERBOXD_URI.MOVIE_ID, LETTERBOXD_URI.RESOLVED_AT)
                .values(val(uri), val(resolution.outcome().name()),
                        val(resolution.tmdbId(), Long.class), currentOffsetDateTime())
                .onConflict(LETTERBOXD_URI.URI)
                .doUpdate()
                .set(LETTERBOXD_URI.OUTCOME, resolution.outcome().name())
                .set(LETTERBOXD_URI.MOVIE_ID, resolution.tmdbId())
                .set(LETTERBOXD_URI.RESOLVED_AT, currentOffsetDateTime())
                .execute();
    }
}
