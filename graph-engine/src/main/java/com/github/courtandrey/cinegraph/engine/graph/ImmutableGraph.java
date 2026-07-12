package com.github.courtandrey.cinegraph.engine.graph;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Function;

public final class ImmutableGraph {

    private static final int MAX_POOLED_WORKSPACES = 32;

    private final long[] idByIdx;
    private final int[] offsets;
    private final IntBuffer neighbors;
    private final FloatBuffer scores;
    private final int[] component;
    private final ArrayBlockingQueue<Workspace> pool = new ArrayBlockingQueue<>(MAX_POOLED_WORKSPACES);

    public ImmutableGraph(long[] idByIdx, int[] offsets, IntBuffer neighbors, FloatBuffer scores) {
        this.idByIdx = idByIdx;
        this.offsets = offsets;
        this.neighbors = neighbors;
        this.scores = scores;
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
        return withWorkspace(ws -> search(from, to, maxHops, null, ws));
    }

    public long[] shortestPathWithin(int from, int to, boolean[] allowed, int maxHops) {
        if (from < 0 || to < 0 || !allowed[from] || !allowed[to]) return null;
        return withWorkspace(ws -> search(from, to, maxHops, allowed, ws));
    }

    public boolean[] allowedMask(java.util.Collection<Long> ids) {
        boolean[] mask = new boolean[idByIdx.length];
        for (long id : ids) {
            int idx = indexOf(id);
            if (idx >= 0) mask[idx] = true;
        }
        return mask;
    }

    public record Scored(long movieId, double score) {}

    public List<Scored> recommend(long[] seedIds, double[] coefs, boolean[] exclude,
                                  int limit, boolean invert) {
        if (limit <= 0) return List.of();
        return withWorkspace(ws -> recommend(seedIds, coefs, exclude, limit, invert, ws));
    }

    private <T> T withWorkspace(Function<Workspace, T> query) {
        Workspace ws = pool.poll();
        if (ws == null) ws = new Workspace(idByIdx.length);
        try {
            return query.apply(ws);
        } finally {
            pool.offer(ws);
        }
    }

    private List<Scored> recommend(long[] seedIds, double[] coefs, boolean[] exclude,
                                   int limit, boolean invert, Workspace ws) {
        float[] acc = ws.accCleared();
        boolean[] seen = ws.seen();
        Workspace.IntList touched = ws.touched;

        for (int i = 0; i < seedIds.length; i++) {
            int u = indexOf(seedIds[i]);
            if (u < 0 || coefs[i] == 0.0) continue;
            double coef = coefs[i];
            for (int e = offsets[u]; e < offsets[u + 1]; e++) {
                int v = neighbors.get(e);
                if (exclude[v]) continue;
                if (!seen[v]) {
                    seen[v] = true;
                    touched.add(v);
                }
                acc[v] += (float) (coef * scores.get(e));
            }
        }

        PriorityQueue<Integer> heap = new PriorityQueue<>(limit + 1,
                Comparator.comparingDouble(v -> invert ? -acc[v] : acc[v]));
        for (int i = 0; i < touched.len; i++) {
            int v = touched.a[i];
            if (!invert && acc[v] <= 0) continue;
            if (heap.size() < limit) {
                heap.offer(v);
            } else {
                double worstKept = invert ? -acc[heap.peek()] : acc[heap.peek()];
                double rank = invert ? -acc[v] : acc[v];
                if (rank > worstKept) {
                    heap.poll();
                    heap.offer(v);
                }
            }
        }

        List<Scored> out = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            int v = heap.poll();
            out.add(new Scored(idByIdx[v], acc[v]));
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private long[] search(int from, int to, int maxHops, boolean[] allowed, Workspace ws) {
        if (from == to) return new long[]{idByIdx[from]};
        int hopCap = Math.min(maxHops, Byte.MAX_VALUE);

        byte[] distF = ws.depthF();
        byte[] distB = ws.depthB();
        int[] parF = ws.parentF();
        int[] parB = ws.parentB();
        distF[from] = 0;
        distB[to] = 0;

        Workspace.IntList frontF = ws.frontF;
        Workspace.IntList frontB = ws.frontB;
        Workspace.IntList spare = ws.spare;
        frontF.len = 0;
        frontF.add(from);
        frontB.len = 0;
        frontB.add(to);
        int dF = 0;
        int dB = 0;
        int best = Integer.MAX_VALUE;
        int meet = -1;

        while (frontF.len > 0 && frontB.len > 0 && dF + dB < best && dF + dB < hopCap) {
            boolean expandF = frontF.len <= frontB.len;
            Workspace.IntList front = expandF ? frontF : frontB;
            byte[] dist = expandF ? distF : distB;
            byte[] other = expandF ? distB : distF;
            int[] par = expandF ? parF : parB;
            int depth = (expandF ? dF : dB) + 1;

            spare.len = 0;
            for (int i = 0; i < front.len; i++) {
                int u = front.a[i];
                for (int e = offsets[u]; e < offsets[u + 1]; e++) {
                    int v = neighbors.get(e);
                    if (allowed != null && !allowed[v]) continue;
                    if (dist[v] != -1) continue;
                    dist[v] = (byte) depth;
                    par[v] = u;
                    spare.add(v);
                    if (other[v] != -1 && depth + other[v] < best) {
                        best = depth + other[v];
                        meet = v;
                    }
                }
            }
            Workspace.IntList emptied = front;
            if (expandF) {
                frontF = spare;
                dF = depth;
            } else {
                frontB = spare;
                dB = depth;
            }
            spare = emptied;
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
