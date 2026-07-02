package com.github.courtandrey.cinegraph.engine.graph;

import java.util.Arrays;

public final class ImmutableGraph {

    private final long[] idByIdx;
    private final int[] offsets;
    private final int[] neighbors;
    private final int[] component;

    public ImmutableGraph(long[] idByIdx, int[] offsets, int[] neighbors) {
        this.idByIdx = idByIdx;
        this.offsets = offsets;
        this.neighbors = neighbors;
        this.component = computeComponents(idByIdx.length, offsets, neighbors);
    }

    public int nodeCount() {
        return idByIdx.length;
    }

    public long edgeCount() {
        return neighbors.length / 2L;
    }

    long[] ids() {
        return idByIdx;
    }

    int[] offsets() {
        return offsets;
    }

    int[] neighbors() {
        return neighbors;
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

        int[] frontF = {from};
        int[] frontB = {to};
        int dF = 0;
        int dB = 0;
        int best = Integer.MAX_VALUE;
        int meet = -1;

        while (frontF.length > 0 && frontB.length > 0 && dF + dB < best && dF + dB < maxHops) {
            boolean expandF = frontF.length <= frontB.length;
            int[] front = expandF ? frontF : frontB;
            int[] dist = expandF ? distF : distB;
            int[] other = expandF ? distB : distF;
            int[] par = expandF ? parF : parB;
            int depth = (expandF ? dF : dB) + 1;

            int[] next = new int[n];
            int count = 0;
            for (int u : front) {
                for (int e = offsets[u]; e < offsets[u + 1]; e++) {
                    int v = neighbors[e];
                    if (dist[v] != -1) continue;
                    dist[v] = depth;
                    par[v] = u;
                    next[count++] = v;
                    if (other[v] != -1 && depth + other[v] < best) {
                        best = depth + other[v];
                        meet = v;
                    }
                }
            }
            int[] trimmed = Arrays.copyOf(next, count);
            if (expandF) {
                frontF = trimmed;
                dF = depth;
            } else {
                frontB = trimmed;
                dB = depth;
            }
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

    private static int[] computeComponents(int n, int[] offsets, int[] neighbors) {
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int u = 0; u < n; u++) {
            for (int e = offsets[u]; e < offsets[u + 1]; e++) {
                union(parent, u, neighbors[e]);
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
