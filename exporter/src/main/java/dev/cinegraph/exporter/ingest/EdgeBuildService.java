package dev.cinegraph.exporter.ingest;

import dev.cinegraph.exporter.admin.RunRegistry;
import dev.cinegraph.exporter.config.ScoringProperties;
import dev.cinegraph.exporter.domain.RunKind;
import dev.cinegraph.exporter.domain.RunStatus;
import dev.cinegraph.exporter.repo.LoadRunRepository;
import dev.cinegraph.exporter.repo.PgSupport;
import io.vavr.control.Try;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.SQLDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static dev.cinegraph.exporter.jooq.Tables.CREDIT;
import static dev.cinegraph.exporter.jooq.Tables.DIRTY_MOVIE;
import static dev.cinegraph.exporter.jooq.Tables.DIRTY_SCOPE;
import static dev.cinegraph.exporter.jooq.Tables.EDGE;
import static dev.cinegraph.exporter.jooq.Tables.EDGE_CREW;
import static dev.cinegraph.exporter.jooq.Tables.EDGE_CREW_TMP;
import static dev.cinegraph.exporter.jooq.Tables.GENRE;
import static dev.cinegraph.exporter.jooq.Tables.KEYWORD;
import static dev.cinegraph.exporter.jooq.Tables.MOVIE;
import static dev.cinegraph.exporter.jooq.Tables.MOVIE_GENRE;
import static dev.cinegraph.exporter.jooq.Tables.MOVIE_KEYWORD;
import static dev.cinegraph.exporter.jooq.Tables.PERSON;
import static dev.cinegraph.exporter.jooq.Tables.ROLE;
import static dev.cinegraph.exporter.jooq.Tables.SCORED_CREDIT;
import static org.jooq.impl.DSL.abs;
import static org.jooq.impl.DSL.any;
import static org.jooq.impl.DSL.arrayAgg;
import static org.jooq.impl.DSL.arrayGet;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.greatest;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.jsonEntry;
import static org.jooq.impl.DSL.jsonbArray;
import static org.jooq.impl.DSL.jsonbArrayAgg;
import static org.jooq.impl.DSL.jsonbGetAttributeAsText;
import static org.jooq.impl.DSL.jsonbObject;
import static org.jooq.impl.DSL.lateral;
import static org.jooq.impl.DSL.least;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.trueCondition;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * Edge generation. All heavy work runs inside PostgreSQL; Java orchestrates DSL
 * statements across hash buckets so memory stays bounded and progress is
 * checkpointable. Full and incremental builds share UNLOGGED scratch tables and
 * therefore never run concurrently.
 */
@Service
public class EdgeBuildService {

    private static final Logger log = LoggerFactory.getLogger(EdgeBuildService.class);
    private static final long PASS2_CHUNK = 50_000L;
    private static final int DELETE_CHUNK = 5_000;
    private static final int PERSONS_KEPT_PER_EDGE = 25;
    private static final int KEYWORD_SAMPLE = 10;

    private final DSLContext ctx;
    private final LoadRunRepository runRepo;
    private final RunRegistry runRegistry;
    private final ScoringProperties scoring;

    public EdgeBuildService(DSLContext ctx, LoadRunRepository runRepo,
                            RunRegistry runRegistry, ScoringProperties scoring) {
        this.ctx = ctx;
        this.runRepo = runRepo;
        this.runRegistry = runRegistry;
        this.scoring = scoring;
    }

    public long triggerFullRebuild() {
        requireNoEdgeBuildRunning();
        long runId = runRepo.create(RunKind.EDGE_FULL);
        runRegistry.launch(runId, () -> runBuild(runId, null, runId));
        return runId;
    }

    public long triggerIncrementalEdges(long ingestRunId) {
        requireNoEdgeBuildRunning();
        long runId = runRepo.create(RunKind.EDGE_INCREMENTAL);
        runRegistry.launch(runId, () -> runBuild(runId, ingestRunId, runId));
        return runId;
    }

    public void runIncrementalEdgesSync(long ingestRunId) {
        if (Try.run(this::requireNoEdgeBuildRunning).isFailure()) {
            log.warn("Skipping incremental edge maintenance for ingest run {}: another edge build is running",
                    ingestRunId);
            return;
        }
        long runId = runRepo.create(RunKind.EDGE_INCREMENTAL);
        runBuild(runId, ingestRunId, ingestRunId);
    }

    private void requireNoEdgeBuildRunning() {
        if (runRepo.hasRunning(RunKind.EDGE_FULL, RunKind.EDGE_INCREMENTAL)) {
            throw new IllegalStateException("An edge build is already running");
        }
    }

    private void runBuild(long runId, Long ingestRunId, long cancelKey) {
        boolean incremental = ingestRunId != null;
        log.info("[edge run {}] {} edge build started", runId, incremental ? "Incremental" : "Full");
        Stats stats = Stats.ZERO;
        try {
            if (incremental) {
                List<Long> dirtyIds = ctx.select(DIRTY_MOVIE.MOVIE_ID).from(DIRTY_MOVIE)
                        .where(DIRTY_MOVIE.RUN_ID.eq(ingestRunId))
                        .fetch(DIRTY_MOVIE.MOVIE_ID);
                if (dirtyIds.isEmpty()) {
                    runRepo.finish(runId, RunStatus.COMPLETED, "{\"dirty_ids\":0}");
                    return;
                }
                stats = stats.plusDeleted(deleteEdgesTouching(dirtyIds));
                seedDirtyScope(dirtyIds);
            } else {
                ctx.truncate(EDGE).execute();
            }

            if (cancelled(cancelKey)) { runRepo.finish(runId, RunStatus.CANCELLED, stats.toJson()); return; }

            buildScoredCredit(incremental);
            stats = runPass1(runId, incremental, cancelKey, stats);
            if (cancelled(cancelKey)) { runRepo.finish(runId, RunStatus.CANCELLED, stats.toJson()); return; }

            mergeEdgeCrew();
            stats = runPass2(runId, incremental, cancelKey, stats);

            runRepo.finish(runId, RunStatus.COMPLETED, stats.toJson());
            if (incremental) {
                ctx.deleteFrom(DIRTY_MOVIE).where(DIRTY_MOVIE.RUN_ID.eq(ingestRunId)).execute();
            }
            log.info("[edge run {}] Edge build completed; {}", runId, stats.toJson());
        } catch (Exception e) {
            log.error("[edge run {}] Edge build failed: {}", runId, e.getMessage(), e);
            runRepo.finish(runId, RunStatus.FAILED, stats.toJson());
        } finally {
            truncateScratch();
        }
    }

    private boolean cancelled(long cancelKey) {
        return runRegistry.isCancelled(cancelKey);
    }

    private void truncateScratch() {
        Try.run(() -> {
            ctx.truncate(SCORED_CREDIT).execute();
            ctx.truncate(EDGE_CREW_TMP).execute();
            ctx.truncate(EDGE_CREW).execute();
            ctx.truncate(DIRTY_SCOPE).execute();
        }).onFailure(e -> log.warn("Could not truncate scratch tables: {}", e.getMessage()));
    }

    private void seedDirtyScope(List<Long> dirtyIds) {
        ctx.truncate(DIRTY_SCOPE).execute();
        var insert = ctx.insertInto(DIRTY_SCOPE, DIRTY_SCOPE.MOVIE_ID).values((Long) null);
        var batch = ctx.batch(insert);
        dirtyIds.forEach(batch::bind);
        batch.execute();
    }

    private int deleteEdgesTouching(List<Long> movieIds) {
        int deleted = 0;
        for (int i = 0; i < movieIds.size(); i += DELETE_CHUNK) {
            Long[] chunk = movieIds.subList(i, Math.min(i + DELETE_CHUNK, movieIds.size()))
                    .toArray(Long[]::new);
            deleted += ctx.deleteFrom(EDGE)
                    .where(EDGE.MOVIE_A.eq(any(chunk)).or(EDGE.MOVIE_B.eq(any(chunk))))
                    .execute();
        }
        return deleted;
    }

    private void buildScoredCredit(boolean incremental) {
        ctx.truncate(SCORED_CREDIT).execute();
        ctx.dropIndexIfExists("idx_scored_credit_person").execute();

        var cr = CREDIT.as("cr");
        var rw2 = ROLE.as("rw2");
        var c2 = CREDIT.as("c2");

        Condition minorCast = scoring.isIncludeMinorCast()
                ? noCondition()
                : cr.ROLE.ne(inline("CAST_MINOR"));
        Condition dirtyPersons = incremental
                ? exists(selectOne().from(c2)
                        .join(DIRTY_SCOPE).on(DIRTY_SCOPE.MOVIE_ID.eq(c2.MOVIE_ID))
                        .where(c2.PERSON_ID.eq(cr.PERSON_ID)))
                : noCondition();

        Table<?> best = select(
                        cr.MOVIE_ID.as("movie_id"),
                        cr.PERSON_ID.as("person_id"),
                        arrayGet(arrayAgg(cr.ROLE).orderBy(rw2.BASE_WEIGHT.desc()), 1).as("role"))
                .from(cr)
                .join(rw2).on(rw2.CODE.eq(cr.ROLE))
                .where(minorCast).and(dirtyPersons)
                .groupBy(cr.MOVIE_ID, cr.PERSON_ID)
                .asTable("c");

        Field<String> bestRole = best.field("role", String.class);
        ctx.insertInto(SCORED_CREDIT,
                        SCORED_CREDIT.MOVIE_ID, SCORED_CREDIT.PERSON_ID,
                        SCORED_CREDIT.ROLE, SCORED_CREDIT.BASE_WEIGHT)
                .select(select(best.field("movie_id", Long.class),
                                best.field("person_id", Long.class),
                                bestRole, ROLE.BASE_WEIGHT)
                        .from(best)
                        .join(ROLE).on(ROLE.CODE.eq(bestRole)))
                .execute();

        ctx.deleteFrom(SCORED_CREDIT).where(SCORED_CREDIT.PERSON_ID.in(
                select(SCORED_CREDIT.PERSON_ID).from(SCORED_CREDIT)
                        .groupBy(SCORED_CREDIT.PERSON_ID)
                        .having(count().gt(scoring.getMaxCreditsPerPerson()))))
                .execute();

        ctx.createIndexIfNotExists("idx_scored_credit_person")
                .on(SCORED_CREDIT, SCORED_CREDIT.PERSON_ID)
                .execute();
        PgSupport.analyze(ctx, SCORED_CREDIT);
    }

    private Stats runPass1(long runId, boolean incremental, long cancelKey, Stats stats) {
        ctx.truncate(EDGE_CREW_TMP).execute();
        for (int bucket = 0; bucket < scoring.getBuckets() && !cancelled(cancelKey); bucket++) {
            stats = stats.plusPairs(insertPass1Bucket(bucket, incremental));
            if (bucket % 8 == 7) {
                runRepo.updateStats(runId, stats.phaseJson("pass1", bucket + 1, scoring.getBuckets()));
            }
        }
        log.info("[edge run {}] Pass 1 complete; {} raw pair rows", runId, stats.pass1Pairs());
        return stats;
    }

    private int insertPass1Bucket(int bucket, boolean incremental) {
        var a = SCORED_CREDIT.as("a");
        var b = SCORED_CREDIT.as("b");
        float cap = (float) scoring.getPerPersonCap();

        Field<Float> contrib = least(inline(cap),
                when(a.ROLE.eq(b.ROLE), a.BASE_WEIGHT.mul(inline(1.5f)))
                        .otherwise(least(a.BASE_WEIGHT, b.BASE_WEIGHT)));

        Field<JSONB> personObj = jsonbObject(
                jsonEntry(inline("type"), inline("SHARED_PERSON")),
                jsonEntry(inline("personId"), a.PERSON_ID),
                jsonEntry(inline("name"), PERSON.NAME),
                jsonEntry(inline("roleA"), a.ROLE),
                jsonEntry(inline("roleB"), b.ROLE),
                jsonEntry(inline("sameRole"), field(a.ROLE.eq(b.ROLE))),
                jsonEntry(inline("score"), contrib));

        Condition dirty = incremental
                ? a.MOVIE_ID.in(select(DIRTY_SCOPE.MOVIE_ID).from(DIRTY_SCOPE))
                        .or(b.MOVIE_ID.in(select(DIRTY_SCOPE.MOVIE_ID).from(DIRTY_SCOPE)))
                : noCondition();

        Table<?> pairs = select(
                        least(a.MOVIE_ID, b.MOVIE_ID).as("movie_a"),
                        greatest(a.MOVIE_ID, b.MOVIE_ID).as("movie_b"),
                        contrib.as("contrib"),
                        personObj.as("person_obj"))
                .from(a)
                .join(b).on(a.PERSON_ID.eq(b.PERSON_ID)).and(a.MOVIE_ID.lt(b.MOVIE_ID))
                .join(PERSON).on(PERSON.PERSON_ID.eq(a.PERSON_ID))
                .where(a.PERSON_ID.mod(inline((long) scoring.getBuckets())).eq(val((long) bucket)))
                .and(dirty)
                .asTable("pairs");

        Field<Long> pa = pairs.field("movie_a", Long.class);
        Field<Long> pb = pairs.field("movie_b", Long.class);
        Field<Float> pc = pairs.field("contrib", Float.class);
        Field<JSONB> po = pairs.field("person_obj", JSONB.class);

        return ctx.insertInto(EDGE_CREW_TMP,
                        EDGE_CREW_TMP.MOVIE_A, EDGE_CREW_TMP.MOVIE_B,
                        EDGE_CREW_TMP.CREW_SCORE, EDGE_CREW_TMP.PERSONS)
                .select(select(pa, pb,
                                sum(pc).cast(SQLDataType.REAL),
                                jsonbArrayAgg(po).orderBy(pc.desc()))
                        .from(pairs)
                        .groupBy(pa, pb))
                .execute();
    }

    private void mergeEdgeCrew() {
        ctx.truncate(EDGE_CREW).execute();
        ctx.dropIndexIfExists("idx_edge_crew_a").execute();

        float minCrew = (float) scoring.getMinCrewScore();
        Field<Float> sum = sum(EDGE_CREW_TMP.CREW_SCORE).cast(SQLDataType.REAL);

        Table<?> agg = select(
                        EDGE_CREW_TMP.MOVIE_A.as("movie_a"),
                        EDGE_CREW_TMP.MOVIE_B.as("movie_b"),
                        sum.as("crew_score"),
                        PgSupport.jsonbConcatAgg(EDGE_CREW_TMP.PERSONS).as("all_persons"))
                .from(EDGE_CREW_TMP)
                .groupBy(EDGE_CREW_TMP.MOVIE_A, EDGE_CREW_TMP.MOVIE_B)
                .having(sum.ge(inline(minCrew)))
                .asTable("agg");

        Field<JSONB> element = field(name("e", "e"), JSONB.class);
        Field<Float> elementScore = jsonbGetAttributeAsText(element, "score").cast(SQLDataType.REAL);
        Table<?> top = select(element.as("e"))
                .from(PgSupport.jsonbArrayElements(agg.field("all_persons", JSONB.class)))
                .orderBy(elementScore.desc())
                .limit(PERSONS_KEPT_PER_EDGE)
                .asTable("sub");
        Field<JSONB> kept = top.field("e", JSONB.class);
        Field<Float> keptScore = jsonbGetAttributeAsText(kept, "score").cast(SQLDataType.REAL);
        Field<JSONB> trimmed = field(select(jsonbArrayAgg(kept).orderBy(keptScore.desc())).from(top));

        ctx.insertInto(EDGE_CREW,
                        EDGE_CREW.MOVIE_A, EDGE_CREW.MOVIE_B, EDGE_CREW.CREW_SCORE, EDGE_CREW.PERSONS)
                .select(select(agg.field("movie_a", Long.class),
                                agg.field("movie_b", Long.class),
                                agg.field("crew_score", Float.class),
                                trimmed)
                        .from(agg))
                .execute();

        ctx.createIndexIfNotExists("idx_edge_crew_a").on(EDGE_CREW, EDGE_CREW.MOVIE_A).execute();
        PgSupport.analyze(ctx, EDGE_CREW);
    }

    private Stats runPass2(long runId, boolean incremental, long cancelKey, Stats stats) {
        long maxMovieA = ctx.select(greatest(inline(0L), org.jooq.impl.DSL.max(EDGE_CREW.MOVIE_A)))
                .from(EDGE_CREW)
                .fetchOptional(0, Long.class).orElse(0L);

        for (long start = 0; start <= maxMovieA && !cancelled(cancelKey); start += PASS2_CHUNK) {
            stats = stats.plusInserted(insertPass2Chunk(start, start + PASS2_CHUNK - 1, incremental));
        }
        log.info("[edge run {}] Pass 2 complete; {} edges total", runId, stats.edgesInserted());
        return stats;
    }

    private int insertPass2Chunk(long chunkStart, long chunkEnd, boolean incremental) {
        var ec = EDGE_CREW.as("ec");
        JSONB emptyArray = JSONB.valueOf("[]");

        Table<?> g = genreLateral(ec);
        Table<?> k = keywordLateral(ec);
        Table<?> d = dateLateral(ec);

        Field<Float> gScore = g.field("score", Float.class);
        Field<Float> kScore = k.field("score", Float.class);
        Field<Float> dScore = d.field("score", Float.class);
        Field<Float> total = ec.CREW_SCORE.plus(gScore).plus(kScore).plus(dScore);

        @SuppressWarnings("unchecked")
        Field<JSONB> components = PgSupport.jsonbConcat(ec.PERSONS,
                g.field("component", JSONB.class),
                k.field("component", JSONB.class),
                d.field("component", JSONB.class));

        Condition dirty = incremental
                ? ec.MOVIE_A.in(select(DIRTY_SCOPE.MOVIE_ID).from(DIRTY_SCOPE))
                        .or(ec.MOVIE_B.in(select(DIRTY_SCOPE.MOVIE_ID).from(DIRTY_SCOPE)))
                : noCondition();

        return ctx.insertInto(EDGE,
                        EDGE.MOVIE_A, EDGE.MOVIE_B, EDGE.TOTAL_SCORE, EDGE.CREW_SCORE,
                        EDGE.COMPONENTS, EDGE.COMPUTED_AT)
                .select(select(ec.MOVIE_A, ec.MOVIE_B, total, ec.CREW_SCORE,
                                components, currentOffsetDateTime())
                        .from(ec)
                        .leftJoin(lateral(g)).on(trueCondition())
                        .leftJoin(lateral(k)).on(trueCondition())
                        .leftJoin(lateral(d)).on(trueCondition())
                        .where(ec.MOVIE_A.between(val(chunkStart), val(chunkEnd)))
                        .and(dirty))
                .onConflict(EDGE.MOVIE_A, EDGE.MOVIE_B)
                .doUpdate()
                .set(EDGE.TOTAL_SCORE, excluded(EDGE.TOTAL_SCORE))
                .set(EDGE.CREW_SCORE, excluded(EDGE.CREW_SCORE))
                .set(EDGE.COMPONENTS, excluded(EDGE.COMPONENTS))
                .set(EDGE.COMPUTED_AT, excluded(EDGE.COMPUTED_AT))
                .execute();
    }

    private Table<?> genreLateral(dev.cinegraph.exporter.jooq.tables.EdgeCrew ec) {
        var mgA = MOVIE_GENRE.as("mg_a");
        var mgB = MOVIE_GENRE.as("mg_b");

        Table<?> gs = select(count().as("cnt"),
                        jsonbArrayAgg(GENRE.GENRE_ID).orderBy(GENRE.GENRE_ID).as("genre_ids"),
                        jsonbArrayAgg(GENRE.NAME).orderBy(GENRE.GENRE_ID).as("genre_names"))
                .from(mgA)
                .join(mgB).on(mgA.GENRE_ID.eq(mgB.GENRE_ID)).and(mgB.MOVIE_ID.eq(ec.MOVIE_B))
                .join(GENRE).on(GENRE.GENRE_ID.eq(mgA.GENRE_ID))
                .where(mgA.MOVIE_ID.eq(ec.MOVIE_A))
                .asTable("gs");

        Field<Integer> cnt = gs.field("cnt", Integer.class);
        Field<Float> score = least(inline(6.0f), cnt.cast(SQLDataType.REAL).mul(inline(2.0f)));
        Field<JSONB> component = when(cnt.gt(inline(0)),
                jsonbArray(jsonbObject(
                        jsonEntry(inline("type"), inline("SHARED_GENRES")),
                        jsonEntry(inline("genreIds"), gs.field("genre_ids", JSONB.class)),
                        jsonEntry(inline("names"), gs.field("genre_names", JSONB.class)),
                        jsonEntry(inline("score"), score))))
                .otherwise(inline(JSONB.valueOf("[]")));

        return select(score.as("score"), component.as("component")).from(gs).asTable("g");
    }

    private Table<?> keywordLateral(dev.cinegraph.exporter.jooq.tables.EdgeCrew ec) {
        var mkA = MOVIE_KEYWORD.as("mk_a");
        var mkB = MOVIE_KEYWORD.as("mk_b");
        var mkA2 = MOVIE_KEYWORD.as("mk_a2");
        var mkB2 = MOVIE_KEYWORD.as("mk_b2");

        Table<?> ks = select(count().as("cnt"))
                .from(mkA)
                .join(mkB).on(mkA.KEYWORD_ID.eq(mkB.KEYWORD_ID)).and(mkB.MOVIE_ID.eq(ec.MOVIE_B))
                .where(mkA.MOVIE_ID.eq(ec.MOVIE_A))
                .asTable("ks");

        Table<?> top = select(mkA2.KEYWORD_ID.as("keyword_id"))
                .from(mkA2)
                .join(mkB2).on(mkA2.KEYWORD_ID.eq(mkB2.KEYWORD_ID)).and(mkB2.MOVIE_ID.eq(ec.MOVIE_B))
                .where(mkA2.MOVIE_ID.eq(ec.MOVIE_A))
                .limit(KEYWORD_SAMPLE)
                .asTable("top10");
        Field<JSONB> sampleNames = field(select(jsonbArrayAgg(KEYWORD.NAME))
                .from(top)
                .join(KEYWORD).on(KEYWORD.KEYWORD_ID.eq(top.field("keyword_id", Integer.class))));

        Field<Integer> cnt = ks.field("cnt", Integer.class);
        Field<Float> score = least(inline(5.0f), cnt.cast(SQLDataType.REAL).mul(inline(0.5f)));
        Field<JSONB> component = when(cnt.gt(inline(0)),
                jsonbArray(jsonbObject(
                        jsonEntry(inline("type"), inline("SHARED_KEYWORDS")),
                        jsonEntry(inline("count"), cnt),
                        jsonEntry(inline("sampleNames"), sampleNames),
                        jsonEntry(inline("score"), score))))
                .otherwise(inline(JSONB.valueOf("[]")));

        return select(score.as("score"), component.as("component")).from(ks).asTable("k");
    }

    private Table<?> dateLateral(dev.cinegraph.exporter.jooq.tables.EdgeCrew ec) {
        var ma = MOVIE.as("ma");
        var mb = MOVIE.as("mb");

        Condition bothYears = ma.RELEASE_YEAR.isNotNull().and(mb.RELEASE_YEAR.isNotNull());
        Field<Integer> delta = abs(ma.RELEASE_YEAR.cast(SQLDataType.INTEGER)
                .minus(mb.RELEASE_YEAR.cast(SQLDataType.INTEGER)));
        Field<Float> proximity = greatest(inline(0.0f),
                inline(4.0f).mul(inline(1.0f).minus(delta.cast(SQLDataType.REAL).div(inline(25.0f)))));

        Field<Float> score = when(bothYears, proximity).otherwise(inline(0.0f));
        Field<JSONB> component = when(bothYears,
                jsonbArray(jsonbObject(
                        jsonEntry(inline("type"), inline("RELEASE_PROXIMITY")),
                        jsonEntry(inline("deltaYears"), delta),
                        jsonEntry(inline("score"), proximity))))
                .otherwise(inline(JSONB.valueOf("[]")));

        return select(score.as("score"), component.as("component"))
                .from(ma)
                .crossJoin(mb)
                .where(ma.MOVIE_ID.eq(ec.MOVIE_A)).and(mb.MOVIE_ID.eq(ec.MOVIE_B))
                .asTable("d");
    }

    private record Stats(long pass1Pairs, long edgesInserted, long edgesDeleted) {
        static final Stats ZERO = new Stats(0, 0, 0);

        Stats plusPairs(long n) {
            return new Stats(pass1Pairs + n, edgesInserted, edgesDeleted);
        }

        Stats plusInserted(long n) {
            return new Stats(pass1Pairs, edgesInserted + n, edgesDeleted);
        }

        Stats plusDeleted(long n) {
            return new Stats(pass1Pairs, edgesInserted, edgesDeleted + n);
        }

        String toJson() {
            return "{\"pass1_pairs\":" + pass1Pairs
                    + ",\"edges_inserted\":" + edgesInserted
                    + ",\"edges_deleted\":" + edgesDeleted + "}";
        }

        String phaseJson(String phase, int done, int totalBuckets) {
            return "{\"phase\":\"" + phase + "\",\"buckets_done\":" + done
                    + ",\"buckets_total\":" + totalBuckets
                    + ",\"pairs_so_far\":" + pass1Pairs + "}";
        }
    }
}
