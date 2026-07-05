package com.github.courtandrey.cinegraph.engine.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        int[] cursor = offsets.clone();
        for (long[] e : EDGES) {
            int a = idx(e[0]);
            int b = idx(e[1]);
            nb.put(cursor[a]++, b);
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
    void read_missingOrCorruptFile_isEmpty() throws Exception {
        assertTrue(GraphSnapshot.read(dir.resolve("absent.bin")).isEmpty());

        Path junk = dir.resolve("junk.bin");
        Files.write(junk, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        assertTrue(GraphSnapshot.read(junk).isEmpty());
    }
}
