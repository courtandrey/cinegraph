package com.github.courtandrey.cinegraph.api.repo;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

import static com.github.courtandrey.cinegraph.api.jooq.Tables.EDGE;
import static com.github.courtandrey.cinegraph.api.jooq.Tables.MOVIE;
import static org.jooq.impl.DSL.any;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.select;

@Repository
public class PathQueryRepository {

    private final DSLContext ctx;

    public PathQueryRepository(DSLContext ctx) {
        this.ctx = ctx;
    }

    public Map<Long, Long> components(long a, long b) {
        return ctx.select(MOVIE.MOVIE_ID, MOVIE.COMPONENT_ID)
                .from(MOVIE)
                .where(MOVIE.MOVIE_ID.in(a, b))
                .fetchMap(MOVIE.MOVIE_ID, MOVIE.COMPONENT_ID);
    }

    public List<long[]> expand(Long[] frontier) {
        var forward = select(EDGE.MOVIE_A.as("src"), EDGE.MOVIE_B.as("neighbor"))
                .from(EDGE).where(EDGE.MOVIE_A.eq(any(frontier)));
        var backward = select(EDGE.MOVIE_B.as("src"), EDGE.MOVIE_A.as("neighbor"))
                .from(EDGE).where(EDGE.MOVIE_B.eq(any(frontier)));
        Table<?> n = forward.unionAll(backward).asTable("n");
        Field<Long> neighbor = n.field("neighbor", Long.class);
        Field<Long> src = n.field("src", Long.class);

        return ctx.select(neighbor, min(src))
                .from(n)
                .groupBy(neighbor)
                .fetch(r -> new long[]{ r.value1(), r.value2() });
    }
}
