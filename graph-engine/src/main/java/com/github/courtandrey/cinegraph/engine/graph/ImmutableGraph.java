package com.github.courtandrey.cinegraph.engine.graph;

import java.nio.IntBuffer;
import java.util.Arrays;

public final class ImmutableGraph {

    private final long[] idByIdx;
    private final int[] offsets;
    private final IntBuffer neighbors;
    private final int[] component;

    public ImmutableGraph(long[] idByIdx, int[] offsets, IntBuffer neighbors) {
        this.idByIdx = idByIdx;
        this.offsets = offsets;
        this.neighbors = neighbors;
        this.component = computeComponents(idByIdx.length, offsets, neighbors);
    }

    public int nodeCount() {
        return idByIdx.length;
    }

    public long edgeCount() {
        return neighbors.capacity() / 2L;
    }

    public int indexOf(long movieId) {
        return Arrays.binarySearch(idByIdx, movieId);
    }

    public boolean contains(long movieId) {
        return indexOf(movieId) >= 0;
    }

    public boolean sameComponent(int a, int b) {
        return component[a] == component[b];
    }

    public long[] shortestPath(int from, int to, int maxHops) {
        return search(from, to, maxHops, null);
    }

    public long[] shortestPathWithin(int from, int to, boolean[] allowed, int maxHops) {
        if (from < 0 || to < 0 || !allowed[from] || !allowed[to]) return null;
        return search(from, to, maxHops, allowed);
    }

    public boolean[] allowedMask(java.util.Collection<Long> ids) {
        boolean[] mask = new boolean[idByIdx.length];
        for (long id : ids) {
            int idx = indexOf(id);
            if (idx >= 0) mask[idx] = true;
        }
        return mask;
    }

    private long[] search(int from, int to, int maxHops, boolean[] allowed) {
        if (from == to) return new long[]{idByIdx[from]};

        int n = idByIdx.length;
        int[] distF = new int[n];
        int[] distB = new int[n];
        int[] parF = new int[n];
        int[] parB = new int[n];
        Arrays.fill(distF, -1);
        Arrays.fill(distB, -1);
        distF[from] = 0;
        distB[to] = 0;

        int[] frontF = new int[n];
        int[] frontB = new int[n];
        int[] spare = new int[n];
        frontF[0] = from;
        frontB[0] = to;
        int lenF = 1;
        int lenB = 1;
        int dF = 0;
        int dB = 0;
        int best = Integer.MAX_VALUE;
        int meet = -1;

        while (lenF > 0 && lenB > 0 && dF + dB < best && dF + dB < maxHops) {
            boolean expandF = lenF <= lenB;
            int[] front = expandF ? frontF : frontB;
            int len = expandF ? lenF : lenB;
            int[] dist = expandF ? distF : distB;
            int[] other = expandF ? distB : distF;
            int[] par = expandF ? parF : parB;
            int depth = (expandF ? dF : dB) + 1;

            int count = 0;
            for (int i = 0; i < len; i++) {
                int u = front[i];
                for (int e = offsets[u]; e < offsets[u + 1]; e++) {
                    int v = neighbors.get(e);
                    if (allowed != null && !allowed[v]) continue;
                    if (dist[v] != -1) continue;
                    dist[v] = depth;
                    par[v] = u;
                    spare[count++] = v;
                    if (other[v] != -1 && depth + other[v] < best) {
                        best = depth + other[v];
                        meet = v;
                    }
                }
            }
            if (expandF) {
                frontF = spare;
                lenF = count;
                dF = depth;
            } else {
                frontB = spare;
                lenB = count;
                dB = depth;
            }
            spare = front;
        }

        if (meet < 0) return null;

        java.util.ArrayDeque<Long> path = new java.util.ArrayDeque<>();
        int cur = meet;
        while (distF[cur] != 0) {
            path.addFirst(idByIdx[cur]);
            cur = parF[cur];
        }
        path.addFirst(idByIdx[cur]);
        cur = meet;
        while (distB[cur] != 0) {
            cur = parB[cur];
            path.addLast(idByIdx[cur]);
        }

        long[] out = new long[path.size()];
        int i = 0;
        for (long id : path) out[i++] = id;
        return out;
    }

    private static int[] computeComponents(int n, int[] offsets, IntBuffer neighbors) {
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int u = 0; u < n; u++) {
            for (int e = offsets[u]; e < offsets[u + 1]; e++) {
                union(parent, u, neighbors.get(e));
            }
        }
        for (int i = 0; i < n; i++) parent[i] = find(parent, i);
        return parent;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra == rb) return;
        if (ra < rb) parent[rb] = ra;
        else parent[ra] = rb;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }
}
