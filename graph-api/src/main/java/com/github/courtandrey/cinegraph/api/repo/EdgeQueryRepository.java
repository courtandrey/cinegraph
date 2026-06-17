package com.github.courtandrey.cinegraph.api.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Try;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.github.courtandrey.cinegraph.api.jooq.Tables.EDGE;

@Repository
public class EdgeQueryRepository {

    public record NeighborEdge(long neighborId, long movieA, long movieB,
                               float score, String componentsJson) {}

    public record RawEdge(long movieA, long movieB,
                          float totalScore, float crewScore, JsonNode components) {}

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
