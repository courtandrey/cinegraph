package com.github.courtandrey.cinegraph.engine.graph;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import static com.github.courtandrey.cinegraph.engine.jooq.Tables.EDGE;
import static com.github.courtandrey.cinegraph.engine.jooq.Tables.MOVIE;

@Component
public class GraphLoader {

    private static final Logger log = LoggerFactory.getLogger(GraphLoader.class);

    private final DSLContext ctx;

    public GraphLoader(DSLContext ctx) {
        this.ctx = ctx;
    }

    public ImmutableGraph load() {
        long start = System.currentTimeMillis();

        // (movie_id, degree) ascending by id for the edge-incident films — sizes the CSR offsets.
        var rows = ctx.select(MOVIE.MOVIE_ID, MOVIE.DEGREE)
                .from(MOVIE)
                .where(MOVIE.DEGREE.gt(0))
                .orderBy(MOVIE.MOVIE_ID)
                .fetch();

        int n = rows.size();
        long[] idByIdx = new long[n];
        int[] offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            idByIdx[i] = rows.get(i).value1();
            offsets[i + 1] = offsets[i] + rows.get(i).value2();
        }
        int[] neighbors = new int[offsets[n]];
        int[] cursor = offsets.clone();

        ctx.transaction(cfg -> {
            try (var edges = cfg.dsl().select(EDGE.MOVIE_A, EDGE.MOVIE_B)
                    .from(EDGE)
                    .fetchSize(50_000)
                    .fetchLazy()) {
                for (var e : edges) {
                    int a = Arrays.binarySearch(idByIdx, e.value1());
                    int b = Arrays.binarySearch(idByIdx, e.value2());
                    neighbors[cursor[a]++] = b;
                    neighbors[cursor[b]++] = a;
                }
            }
        });

        ImmutableGraph graph = new ImmutableGraph(idByIdx, offsets, neighbors);
        log.info("[engine] loaded graph: {} nodes, {} edges in {} ms",
                n, graph.edgeCount(), System.currentTimeMillis() - start);
        return graph;
    }
}
