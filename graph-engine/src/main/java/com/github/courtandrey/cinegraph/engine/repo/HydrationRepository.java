package com.github.courtandrey.cinegraph.engine.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.courtandrey.cinegraph.engine.dto.GraphNode;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.github.courtandrey.cinegraph.engine.jooq.Tables.EDGE;
import static com.github.courtandrey.cinegraph.engine.jooq.Tables.MOVIE;

@Repository
public class HydrationRepository {

    private final DSLContext ctx;
    private final ObjectMapper mapper;

    public HydrationRepository(DSLContext ctx, ObjectMapper mapper) {
        this.ctx = ctx;
        this.mapper = mapper;
    }

    public List<GraphNode> findNodes(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return ctx.select(MOVIE.MOVIE_ID, MOVIE.TITLE, MOVIE.RELEASE_YEAR, MOVIE.POSTER_PATH)
                .from(MOVIE)
                .where(MOVIE.MOVIE_ID.in(ids))
                .fetch(r -> new GraphNode(r.value1(), r.value2(),
                        r.value3() == null ? null : r.value3().intValue(), r.value4(), 0.0));
    }

    public Optional<EdgeRow> findEdge(long idA, long idB) {
        long lo = Math.min(idA, idB);
        long hi = Math.max(idA, idB);
        return ctx.select(EDGE.TOTAL_SCORE, EDGE.COMPONENTS)
                .from(EDGE)
                .where(EDGE.MOVIE_A.eq(lo).and(EDGE.MOVIE_B.eq(hi)))
                .fetchOptional(r -> new EdgeRow(r.value1(), parse(r.value2())));
    }

    public boolean exists(long id) {
        return ctx.fetchExists(ctx.selectOne().from(MOVIE).where(MOVIE.MOVIE_ID.eq(id)));
    }

    private JsonNode parse(JSONB components) {
        if (components == null) return mapper.createArrayNode();
        try {
            return mapper.readTree(components.data());
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    public record EdgeRow(Float totalScore, JsonNode components) {}
}
