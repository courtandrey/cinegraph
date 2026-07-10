package com.github.courtandrey.cinegraph.engine.graph;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.Arrays;

import static com.github.courtandrey.cinegraph.engine.jooq.Tables.EDGE;

@Component
public class GraphLoader {

    private static final Logger log = LoggerFactory.getLogger(GraphLoader.class);

    private final DSLContext ctx;

    public GraphLoader(DSLContext ctx) {
        this.ctx = ctx;
    }

    public ImmutableGraph load(Path snapshotTarget) {
        long start = System.currentTimeMillis();

        LongIntCounter degree = new LongIntCounter(1 << 21);
        scanEdges((a, b, score) -> {
            degree.increment(a);
            degree.increment(b);
        });

        log.info("[engine] calculated degrees in {} ms", System.currentTimeMillis() - start);

        int n = degree.size();
        long[] idByIdx = degree.sortedKeys();
        int[] offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            offsets[i + 1] = offsets[i] + degree.get(idByIdx[i]);
        }
        int[] cursor = offsets.clone();

        GraphSnapshot.Builder builder;
        try {
            builder = GraphSnapshot.builder(snapshotTarget, idByIdx, offsets);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            IntBuffer neighbors = builder.neighbors();
            FloatBuffer scores = builder.scores();
            scanEdges((a, b, score) -> {
                int ia = Arrays.binarySearch(idByIdx, a);
                int ib = Arrays.binarySearch(idByIdx, b);
                scores.put(cursor[ia], score);
                neighbors.put(cursor[ia]++, ib);
                scores.put(cursor[ib], score);
                neighbors.put(cursor[ib]++, ia);
            });
            builder.commit();
        } catch (Exception e) {
            builder.abort();
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }

        ImmutableGraph graph = GraphSnapshot.read(snapshotTarget)
                .orElseThrow(() -> new IllegalStateException("snapshot unreadable after build: " + snapshotTarget));
        log.info("[engine] loaded graph from db: {} nodes, {} edges in {} ms; snapshot {}",
                n, graph.edgeCount(), System.currentTimeMillis() - start, snapshotTarget);
        return graph;
    }

    private void scanEdges(EdgeConsumer consumer) {
        ctx.transaction(cfg -> {
            try (var edges = cfg.dsl().select(EDGE.MOVIE_A, EDGE.MOVIE_B, EDGE.TOTAL_SCORE)
                    .from(EDGE)
                    .fetchSize(50_000)
                    .fetchLazy()) {
                for (var e : edges) {
                    consumer.accept(e.value1(), e.value2(), e.value3());
                }
            }
        });
    }

    @FunctionalInterface
    private interface EdgeConsumer {
        void accept(long movieA, long movieB, float score);
    }
}
