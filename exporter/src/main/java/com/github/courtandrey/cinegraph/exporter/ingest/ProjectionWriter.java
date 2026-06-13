package com.github.courtandrey.cinegraph.exporter.ingest;

import com.github.courtandrey.cinegraph.exporter.repo.RoleRepository;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.util.List;

import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.CREDIT;
import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.DIRTY_MOVIE;
import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.GENRE;
import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.KEYWORD;
import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.MOVIE;
import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.MOVIE_GENRE;
import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.MOVIE_KEYWORD;
import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.MOVIE_RAW;
import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.PERSON;
import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.excluded;

@Component
public class ProjectionWriter {

    private final DSLContext ctx;
    private final RoleRepository roleRepository;

    public ProjectionWriter(DSLContext ctx, RoleRepository roleRepository) {
        this.ctx = ctx;
        this.roleRepository = roleRepository;
    }

    @Transactional
    public List<Long> write(Projections.Batch batch, Long dirtyRunId) {
        if (batch.isEmpty()) return batch.projectedIds();

        roleRepository.ensureExist(batch.roleCodes(), Roles.DEFAULT_WEIGHT);
        writeRaws(batch);
        writeMovies(batch);
        writeGenres(batch);
        writeKeywords(batch);
        writePersons(batch);
        writeCredits(batch);
        if (dirtyRunId != null) writeDirty(batch, dirtyRunId);
        return batch.projectedIds();
    }

    private void writeRaws(Projections.Batch batch) {
        var insert = ctx.insertInto(MOVIE_RAW, MOVIE_RAW.MOVIE_ID, MOVIE_RAW.PAYLOAD)
                .values((Long) null, (JSONB) null)
                .onConflict(MOVIE_RAW.MOVIE_ID)
                .doUpdate()
                .set(MOVIE_RAW.PAYLOAD, excluded(MOVIE_RAW.PAYLOAD))
                .set(MOVIE_RAW.FETCHED_AT, currentOffsetDateTime());
        var b = ctx.batch(insert);
        batch.raws().forEach(r -> b.bind(r.movieId(), JSONB.valueOf(r.payload())));
        b.execute();
    }

    private void writeMovies(Projections.Batch batch) {
        var insert = ctx.insertInto(MOVIE,
                        MOVIE.MOVIE_ID, MOVIE.TITLE, MOVIE.ORIGINAL_TITLE, MOVIE.RELEASE_DATE,
                        MOVIE.RELEASE_YEAR, MOVIE.ORIGINAL_LANGUAGE, MOVIE.POPULARITY,
                        MOVIE.VOTE_AVERAGE, MOVIE.VOTE_COUNT, MOVIE.RUNTIME,
                        MOVIE.POSTER_PATH, MOVIE.OVERVIEW, MOVIE.COUNTRIES)
                .values((Long) null, null, null, null, null, null, null, null, null, null, null, null, null)
                .onConflict(MOVIE.MOVIE_ID)
                .doUpdate()
                .set(MOVIE.TITLE, excluded(MOVIE.TITLE))
                .set(MOVIE.ORIGINAL_TITLE, excluded(MOVIE.ORIGINAL_TITLE))
                .set(MOVIE.RELEASE_DATE, excluded(MOVIE.RELEASE_DATE))
                .set(MOVIE.RELEASE_YEAR, excluded(MOVIE.RELEASE_YEAR))
                .set(MOVIE.ORIGINAL_LANGUAGE, excluded(MOVIE.ORIGINAL_LANGUAGE))
                .set(MOVIE.POPULARITY, excluded(MOVIE.POPULARITY))
                .set(MOVIE.VOTE_AVERAGE, excluded(MOVIE.VOTE_AVERAGE))
                .set(MOVIE.VOTE_COUNT, excluded(MOVIE.VOTE_COUNT))
                .set(MOVIE.RUNTIME, excluded(MOVIE.RUNTIME))
                .set(MOVIE.POSTER_PATH, excluded(MOVIE.POSTER_PATH))
                .set(MOVIE.OVERVIEW, excluded(MOVIE.OVERVIEW))
                .set(MOVIE.COUNTRIES, excluded(MOVIE.COUNTRIES))
                .set(MOVIE.UPDATED_AT, currentOffsetDateTime());
        var b = ctx.batch(insert);
        batch.movies().forEach(m -> b.bind(
                m.movieId(), m.title(), m.originalTitle(),
                m.releaseDate() != null ? Date.valueOf(m.releaseDate()) : null,
                m.releaseYear(), m.originalLanguage(), m.popularity(),
                m.voteAverage(), m.voteCount(), m.runtime(),
                m.posterPath(), m.overview(), m.countries()));
        b.execute();
    }

    private void writeGenres(Projections.Batch batch) {
        upsertRefs(batch.genres(), GENRE.GENRE_ID, GENRE.NAME);
        replaceLinks(batch.movieGenres(), MOVIE_GENRE.MOVIE_ID, MOVIE_GENRE.GENRE_ID);
    }

    private void writeKeywords(Projections.Batch batch) {
        upsertRefs(batch.keywords(), KEYWORD.KEYWORD_ID, KEYWORD.NAME);
        replaceLinks(batch.movieKeywords(), MOVIE_KEYWORD.MOVIE_ID, MOVIE_KEYWORD.KEYWORD_ID);
    }

    private void upsertRefs(List<Projections.Ref> refs,
                            org.jooq.TableField<?, Integer> idField,
                            org.jooq.TableField<?, String> nameField) {
        if (refs.isEmpty()) return;
        var insert = ctx.insertInto(idField.getTable())
                .columns(idField, nameField)
                .values((Integer) null, null)
                .onConflict(idField)
                .doUpdate()
                .set(nameField, excluded(nameField));
        var b = ctx.batch(insert);
        refs.forEach(r -> b.bind(r.id(), r.name()));
        b.execute();
    }

    private void replaceLinks(List<Projections.Link> links,
                              org.jooq.TableField<?, Long> movieField,
                              org.jooq.TableField<?, Integer> refField) {
        List<Long> movieIds = links.stream().map(Projections.Link::movieId).distinct().toList();
        if (movieIds.isEmpty()) return;
        ctx.deleteFrom(movieField.getTable()).where(movieField.in(movieIds)).execute();
        var insert = ctx.insertInto(movieField.getTable())
                .columns(movieField, refField)
                .values((Long) null, null);
        var b = ctx.batch(insert);
        links.forEach(l -> b.bind(l.movieId(), l.refId()));
        b.execute();
    }

    private void writePersons(Projections.Batch batch) {
        if (batch.persons().isEmpty()) return;
        var insert = ctx.insertInto(PERSON, PERSON.PERSON_ID, PERSON.NAME, PERSON.PROFILE_PATH)
                .values((Long) null, null, null)
                .onConflict(PERSON.PERSON_ID)
                .doUpdate()
                .set(PERSON.NAME, excluded(PERSON.NAME));
        var b = ctx.batch(insert);
        batch.persons().forEach(p -> b.bind(p.personId(), p.name(), p.profilePath()));
        b.execute();
    }

    private void writeCredits(Projections.Batch batch) {
        List<Long> movieIds = batch.movies().stream().map(Projections.MovieRow::movieId).toList();
        ctx.deleteFrom(CREDIT).where(CREDIT.MOVIE_ID.in(movieIds)).execute();
        if (batch.credits().isEmpty()) return;
        var insert = ctx.insertInto(CREDIT,
                        CREDIT.MOVIE_ID, CREDIT.PERSON_ID, CREDIT.ROLE, CREDIT.JOB,
                        CREDIT.DEPARTMENT, CREDIT.CAST_ORDER, CREDIT.CHARACTER)
                .values((Long) null, null, null, null, null, null, null)
                .onConflict(CREDIT.MOVIE_ID, CREDIT.PERSON_ID, CREDIT.ROLE)
                .doNothing();
        var b = ctx.batch(insert);
        batch.credits().forEach(c -> b.bind(
                c.movieId(), c.personId(), c.role(), c.job(),
                c.department(), c.castOrder(), c.character()));
        b.execute();
    }

    private void writeDirty(Projections.Batch batch, long dirtyRunId) {
        var insert = ctx.insertInto(DIRTY_MOVIE, DIRTY_MOVIE.RUN_ID, DIRTY_MOVIE.MOVIE_ID)
                .values((Long) null, null)
                .onConflict(DIRTY_MOVIE.RUN_ID, DIRTY_MOVIE.MOVIE_ID)
                .doNothing();
        var b = ctx.batch(insert);
        batch.projectedIds().forEach(id -> b.bind(dirtyRunId, id));
        b.execute();
    }
}
