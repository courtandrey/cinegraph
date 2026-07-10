package com.github.courtandrey.cinegraph.engine.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSnapshotTest {

    @TempDir
    Path dir;

    private static final long[] IDS = {10, 20, 30, 40, 50};
    private static final long[][] EDGES = {{10, 20}, {20, 30}, {30, 40}, {10, 50}, {50, 40}};
    private static final float[] SCORES = {5f, 2f, 8f, 1f, 3f};

    private Path buildSnapshot() throws Exception {
        Path target = dir.resolve("graph.bin");
        int n = IDS.length;
        int[] degree = new int[n];
        for (long[] e : EDGES) {
            degree[idx(e[0])]++;
            degree[idx(e[1])]++;
        }
        int[] offsets = new int[n + 1];
        for (int i = 0; i < n; i++) offsets[i + 1] = offsets[i] + degree[i];

        GraphSnapshot.Builder builder = GraphSnapshot.builder(target, IDS, offsets);
        IntBuffer nb = builder.neighbors();
        FloatBuffer sc = builder.scores();
        int[] cursor = offsets.clone();
        for (int i = 0; i < EDGES.length; i++) {
            int a = idx(EDGES[i][0]);
            int b = idx(EDGES[i][1]);
            sc.put(cursor[a], SCORES[i]);
            nb.put(cursor[a]++, b);
            sc.put(cursor[b], SCORES[i]);
            nb.put(cursor[b]++, a);
        }
        builder.commit();
        return target;
    }

    private static int idx(long id) {
        return Arrays.binarySearch(IDS, id);
    }

    @Test
    void roundTrip_countsAndShortestPath() throws Exception {
        Path target = buildSnapshot();
        assertFalse(Files.exists(dir.resolve("graph.bin.tmp")));

        ImmutableGraph g = GraphSnapshot.read(target).orElseThrow();
        assertEquals(5, g.nodeCount());
        assertEquals(5, g.edgeCount());
        assertTrue(g.contains(30));
        assertFalse(g.contains(99));
        assertTrue(g.sameComponent(g.indexOf(10), g.indexOf(40)));

        long[] path = g.shortestPath(g.indexOf(10), g.indexOf(40), 20);
        assertArrayEquals(new long[]{10, 50, 40}, path);
    }

    @Test
    void subsetPath_avoidsDisallowedNodes() throws Exception {
        ImmutableGraph g = GraphSnapshot.read(buildSnapshot()).orElseThrow();

        boolean[] withoutShortcut = g.allowedMask(List.of(10L, 20L, 30L, 40L));
        long[] path = g.shortestPathWithin(g.indexOf(10), g.indexOf(40), withoutShortcut, 20);
        assertArrayEquals(new long[]{10, 20, 30, 40}, path);

        boolean[] broken = g.allowedMask(List.of(10L, 40L));
        assertNull(g.shortestPathWithin(g.indexOf(10), g.indexOf(40), broken, 20));
    }

    @Test
    void recommend_accumulatesCoefWeightedScores_excludingSeedsSet() throws Exception {
        ImmutableGraph g = GraphSnapshot.read(buildSnapshot()).orElseThrow();

        // seeds 10 (coef 2) and 40 (coef 1); exclusion covers the whole "set" {10, 40}.
        // 20: 2×5 = 10;  30: 1×8 = 8;  50: 2×1 + 1×3 = 5.
        List<ImmutableGraph.Scored> top = g.recommend(
                new long[]{10, 40}, new double[]{2, 1}, g.allowedMask(List.of(10L, 40L)), 10, false);

        assertEquals(List.of(20L, 30L, 50L), top.stream().map(ImmutableGraph.Scored::movieId).toList());
        assertEquals(10.0, top.get(0).score(), 1e-9);
        assertEquals(8.0, top.get(1).score(), 1e-9);
        assertEquals(5.0, top.get(2).score(), 1e-9);
    }

    @Test
    void recommend_limitCaps_andUnknownSeedIsSkipped() throws Exception {
        ImmutableGraph g = GraphSnapshot.read(buildSnapshot()).orElseThrow();

        List<ImmutableGraph.Scored> top = g.recommend(
                new long[]{10, 40, 999}, new double[]{2, 1, 5},
                g.allowedMask(List.of(10L, 40L)), 2, false);

        assertEquals(List.of(20L, 30L), top.stream().map(ImmutableGraph.Scored::movieId).toList());
    }

    @Test
    void recommend_negativeCoef_dropsNonPositive_andInvertLeadsWithWorst() throws Exception {
        ImmutableGraph g = GraphSnapshot.read(buildSnapshot()).orElseThrow();
        boolean[] exclude = g.allowedMask(List.of(10L, 40L));

        // 10 disliked (coef −1), 40 liked (coef 1): 20 → −5, 30 → 8, 50 → −1 + 3 = 2.
        List<ImmutableGraph.Scored> normal = g.recommend(
                new long[]{10, 40}, new double[]{-1, 1}, exclude, 10, false);
        assertEquals(List.of(30L, 50L), normal.stream().map(ImmutableGraph.Scored::movieId).toList());

        List<ImmutableGraph.Scored> inverted = g.recommend(
                new long[]{10, 40}, new double[]{-1, 1}, exclude, 10, true);
        assertEquals(List.of(20L, 50L, 30L), inverted.stream().map(ImmutableGraph.Scored::movieId).toList());
        assertEquals(-5.0, inverted.get(0).score(), 1e-9);
    }

    @Test
    void read_missingOrCorruptFile_isEmpty() throws Exception {
        assertTrue(GraphSnapshot.read(dir.resolve("absent.bin")).isEmpty());

        Path junk = dir.resolve("junk.bin");
        Files.write(junk, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        assertTrue(GraphSnapshot.read(junk).isEmpty());
    }
}
