package com.github.courtandrey.cinegraph.api.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Try;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.github.courtandrey.cinegraph.api.jooq.Tables.EDGE;
import static com.github.courtandrey.cinegraph.api.jooq.Tables.LETTERBOXD_SET;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.when;

@Repository
public class EdgeQueryRepository {

    public record NeighborEdge(long neighborId, long movieA, long movieB,
                               float score, String componentsJson) {}

    public record RawEdge(long movieA, long movieB,
                          float totalScore, float crewScore, JsonNode components) {}

    public record Recommendation(long movieId, double score) {}

    public record SetEdge(long setMovieId, double inScore, Double rating) {}

    private static final int INTER_NEIGHBOR_LIMIT = 300;

    private final DSLContext ctx;
    private final ObjectMapper mapper;

    public EdgeQueryRepository(DSLContext ctx, ObjectMapper mapper) {
        this.ctx = ctx;
        this.mapper = mapper;
    }

    public List<NeighborEdge> findNeighborEdges(long centerId, float minScore, int limit) {
        var touchingA = ctx
                .select(EDGE.MOVIE_B.as("neighbor_id"), EDGE.MOVIE_A, EDGE.MOVIE_B,
                        EDGE.TOTAL_SCORE, EDGE.COMPONENTS)
                .from(EDGE)
                .where(EDGE.MOVIE_A.eq(centerId).and(EDGE.TOTAL_SCORE.ge(minScore)));
        var touchingB = ctx
                .select(EDGE.MOVIE_A.as("neighbor_id"), EDGE.MOVIE_A, EDGE.MOVIE_B,
                        EDGE.TOTAL_SCORE, EDGE.COMPONENTS)
                .from(EDGE)
                .where(EDGE.MOVIE_B.eq(centerId).and(EDGE.TOTAL_SCORE.ge(minScore)));

        return touchingA.unionAll(touchingB)
                .orderBy(EDGE.TOTAL_SCORE.desc())
                .limit(limit)
                .fetch(r -> new NeighborEdge(
                        r.get("neighbor_id", Long.class),
                        r.get(EDGE.MOVIE_A),
                        r.get(EDGE.MOVIE_B),
                        r.get(EDGE.TOTAL_SCORE),
                        text(r.get(EDGE.COMPONENTS))));
    }

    public List<NeighborEdge> findInterNeighborEdges(List<Long> neighborIds, float minScore) {
        if (neighborIds.size() < 2) return List.of();
        return ctx.select(EDGE.MOVIE_A, EDGE.MOVIE_B, EDGE.TOTAL_SCORE, EDGE.COMPONENTS)
                .from(EDGE)
                .where(EDGE.MOVIE_A.in(neighborIds)
                        .and(EDGE.MOVIE_B.in(neighborIds))
                        .and(EDGE.TOTAL_SCORE.ge(minScore)))
                .orderBy(EDGE.TOTAL_SCORE.desc())
                .limit(INTER_NEIGHBOR_LIMIT)
                .fetch(r -> new NeighborEdge(
                        r.get(EDGE.MOVIE_A),
                        r.get(EDGE.MOVIE_A),
                        r.get(EDGE.MOVIE_B),
                        r.get(EDGE.TOTAL_SCORE),
                        text(r.get(EDGE.COMPONENTS))));
    }


    public List<NeighborEdge> findNeighborEdgesAmong(long centerId, Collection<Long> subset,
                                                     float minScore, int limit) {
        if (subset.isEmpty()) return List.of();
        var touchingA = ctx
                .select(EDGE.MOVIE_B.as("neighbor_id"), EDGE.MOVIE_A, EDGE.MOVIE_B,
                        EDGE.TOTAL_SCORE, EDGE.COMPONENTS)
                .from(EDGE)
                .where(EDGE.MOVIE_A.eq(centerId)
                        .and(EDGE.MOVIE_B.in(subset))
                        .and(EDGE.TOTAL_SCORE.ge(minScore)));
        var touchingB = ctx
                .select(EDGE.MOVIE_A.as("neighbor_id"), EDGE.MOVIE_A, EDGE.MOVIE_B,
                        EDGE.TOTAL_SCORE, EDGE.COMPONENTS)
                .from(EDGE)
                .where(EDGE.MOVIE_B.eq(centerId)
                        .and(EDGE.MOVIE_A.in(subset))
                        .and(EDGE.TOTAL_SCORE.ge(minScore)));

        return touchingA.unionAll(touchingB)
                .orderBy(EDGE.TOTAL_SCORE.desc())
                .limit(limit)
                .fetch(r -> new NeighborEdge(
                        r.get("neighbor_id", Long.class),
                        r.get(EDGE.MOVIE_A),
                        r.get(EDGE.MOVIE_B),
                        r.get(EDGE.TOTAL_SCORE),
                        text(r.get(EDGE.COMPONENTS))));
    }

    public List<NeighborEdge> findNeighborEdgesByContribution(long recId, String hash, int limit) {
        var rated = LETTERBOXD_SET.as("rated");
        Field<Double> contribution = EDGE.TOTAL_SCORE.cast(Double.class)
                .mul(ratingCoef(rated.RATING)).as("contribution");

        var fromA = ctx.select(EDGE.MOVIE_B.as("neighbor_id"), EDGE.MOVIE_A, EDGE.MOVIE_B,
                        EDGE.TOTAL_SCORE, EDGE.COMPONENTS, contribution)
                .from(EDGE)
                .join(rated).on(rated.HASH.eq(hash).and(rated.MOVIE_ID.eq(EDGE.MOVIE_B)))
                .where(EDGE.MOVIE_A.eq(recId));
        var fromB = ctx.select(EDGE.MOVIE_A.as("neighbor_id"), EDGE.MOVIE_A, EDGE.MOVIE_B,
                        EDGE.TOTAL_SCORE, EDGE.COMPONENTS, contribution)
                .from(EDGE)
                .join(rated).on(rated.HASH.eq(hash).and(rated.MOVIE_ID.eq(EDGE.MOVIE_A)))
                .where(EDGE.MOVIE_B.eq(recId));

        Table<?> nbr = fromA.unionAll(fromB).asTable("nbr");
        return ctx.select(nbr.fields())
                .from(nbr)
                .orderBy(nbr.field("contribution", Double.class).desc())
                .limit(limit)
                .fetch(r -> new NeighborEdge(
                        r.get("neighbor_id", Long.class),
                        r.get("movie_a", Long.class),
                        r.get("movie_b", Long.class),
                        r.get("total_score", Float.class),
                        text(r.get("components", JSONB.class))));
    }

    public List<NeighborEdge> findEdgesAmong(List<Long> ids) {
        if (ids.size() < 2) return List.of();
        return ctx.select(EDGE.MOVIE_A, EDGE.MOVIE_B, EDGE.TOTAL_SCORE, EDGE.COMPONENTS)
                .from(EDGE)
                .where(EDGE.MOVIE_A.in(ids).and(EDGE.MOVIE_B.in(ids)))
                .orderBy(EDGE.TOTAL_SCORE.desc())
                .fetch(r -> new NeighborEdge(
                        r.get(EDGE.MOVIE_A),
                        r.get(EDGE.MOVIE_A),
                        r.get(EDGE.MOVIE_B),
                        r.get(EDGE.TOTAL_SCORE),
                        text(r.get(EDGE.COMPONENTS))));
    }

    public List<Recommendation> topRecommendations(String hash, Long graphId, boolean invert, int limit) {
        var rated = LETTERBOXD_SET.as("rated");
        var owned = LETTERBOXD_SET.as("owned");
        Condition scope = graphId == null ? noCondition() : rated.GRAPH_ID.eq(graphId);
        Field<Double> contribution = EDGE.TOTAL_SCORE.cast(Double.class)
                .mul(ratingCoef(rated.RATING)).as("contribution");

        var fromA = ctx.select(EDGE.MOVIE_B.as("rec_id"), contribution)
                .from(EDGE)
                .join(rated).on(rated.HASH.eq(hash).and(rated.MOVIE_ID.eq(EDGE.MOVIE_A)).and(scope))
                .whereNotExists(selectOne().from(owned)
                        .where(owned.HASH.eq(hash).and(owned.MOVIE_ID.eq(EDGE.MOVIE_B))));
        var fromB = ctx.select(EDGE.MOVIE_A.as("rec_id"), contribution)
                .from(EDGE)
                .join(rated).on(rated.HASH.eq(hash).and(rated.MOVIE_ID.eq(EDGE.MOVIE_B)).and(scope))
                .whereNotExists(selectOne().from(owned)
                        .where(owned.HASH.eq(hash).and(owned.MOVIE_ID.eq(EDGE.MOVIE_A))));

        Table<?> contrib = fromA.unionAll(fromB).asTable("contrib");
        Field<Long> recId = contrib.field("rec_id", Long.class);
        Field<Double> score = sum(contrib.field("contribution", Double.class)).cast(Double.class);

        var grouped = ctx.select(recId, score.as("score"))
                .from(contrib)
                .groupBy(recId)
                .having(invert ? noCondition() : score.gt(0.0));
        return grouped.orderBy(invert ? score.asc() : score.desc())
                .limit(limit)
                .fetch(r -> new Recommendation(
                        r.get("rec_id", Long.class),
                        r.get("score", Double.class)));
    }

    private static Field<Double> ratingCoef(Field<Float> rating) {
        return when(rating.isNull(), 1.0)
                .otherwise(rating.cast(Double.class).minus(2.5).mul(4.0).plus(1.0));
    }

    public List<SetEdge> recommendationContributions(String hash, long recId) {
        var rated = LETTERBOXD_SET.as("rated");
        var fromA = ctx.select(EDGE.MOVIE_B.as("set_id"), EDGE.TOTAL_SCORE.as("in_score"), rated.RATING)
                .from(EDGE)
                .join(rated).on(rated.HASH.eq(hash).and(rated.MOVIE_ID.eq(EDGE.MOVIE_B)))
                .where(EDGE.MOVIE_A.eq(recId));
        var fromB = ctx.select(EDGE.MOVIE_A.as("set_id"), EDGE.TOTAL_SCORE.as("in_score"), rated.RATING)
                .from(EDGE)
                .join(rated).on(rated.HASH.eq(hash).and(rated.MOVIE_ID.eq(EDGE.MOVIE_A)))
                .where(EDGE.MOVIE_B.eq(recId));
        return fromA.unionAll(fromB)
                .fetch(r -> new SetEdge(
                        r.get("set_id", Long.class),
                        r.get("in_score", Double.class),
                        r.get("rating", Double.class)));
    }

    public Optional<RawEdge> findEdge(long idA, long idB) {
        long lo = Math.min(idA, idB);
        long hi = Math.max(idA, idB);
        return ctx.select(EDGE.MOVIE_A, EDGE.MOVIE_B, EDGE.TOTAL_SCORE, EDGE.CREW_SCORE, EDGE.COMPONENTS)
                .from(EDGE)
                .where(EDGE.MOVIE_A.eq(lo).and(EDGE.MOVIE_B.eq(hi)))
                .fetchOptional(r -> new RawEdge(
                        r.get(EDGE.MOVIE_A),
                        r.get(EDGE.MOVIE_B),
                        r.get(EDGE.TOTAL_SCORE),
                        r.get(EDGE.CREW_SCORE),
                        parse(r.get(EDGE.COMPONENTS))));
    }

    private JsonNode parse(JSONB components) {
        return Try.of(() -> mapper.readTree(text(components)))
                .getOrElse(mapper::createArrayNode);
    }

    private static String text(JSONB components) {
        return components == null ? "[]" : components.data();
    }
}
