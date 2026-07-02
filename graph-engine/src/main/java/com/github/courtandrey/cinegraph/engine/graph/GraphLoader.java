package com.github.courtandrey.cinegraph.engine.graph;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.github.courtandrey.cinegraph.engine.jooq.Tables.EDGE;

@Component
public class GraphLoader {

    private static final Logger log = LoggerFactory.getLogger(GraphLoader.class);

    private final DSLContext ctx;

    public GraphLoader(DSLContext ctx) {
        this.ctx = ctx;
    }

    public ImmutableGraph load() {
        long start = System.currentTimeMillis();

        Map<Long, Integer> degree = new HashMap<>(1 << 21);
        scanEdges((a, b) -> {
            degree.merge(a, 1, Integer::sum);
            degree.merge(b, 1, Integer::sum);
        });

        log.info("[engine] calculated degrees in {} ms", System.currentTimeMillis() - start);

        int n = degree.size();
        long[] idByIdx = degree.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        int[] offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            offsets[i + 1] = offsets[i] + degree.get(idByIdx[i]);
        }
        int[] neighbors = new int[offsets[n]];
        int[] cursor = offsets.clone();

        scanEdges((a, b) -> {
            int ia = Arrays.binarySearch(idByIdx, a);
            int ib = Arrays.binarySearch(idByIdx, b);
            neighbors[cursor[ia]++] = ib;
            neighbors[cursor[ib]++] = ia;
        });

        ImmutableGraph graph = new ImmutableGraph(idByIdx, offsets, neighbors);
        log.info("[engine] loaded graph from db: {} nodes, {} edges in {} ms",
                n, graph.edgeCount(), System.currentTimeMillis() - start);
        return graph;
    }

    private void scanEdges(EdgeConsumer consumer) {
        ctx.transaction(cfg -> {
            try (var edges = cfg.dsl().select(EDGE.MOVIE_A, EDGE.MOVIE_B)
                    .from(EDGE)
                    .fetchSize(50_000)
                    .fetchLazy()) {
                for (var e : edges) {
                    consumer.accept(e.value1(), e.value2());
                }
            }
        });
    }

    @FunctionalInterface
    private interface EdgeConsumer {
        void accept(long movieA, long movieB);
    }
}
