package com.github.courtandrey.cinegraph.engine.graph;

import java.util.Arrays;

final class Workspace {

    final int n;

    private byte[] depthF;
    private byte[] depthB;
    private int[] parentF;
    private int[] parentB;
    private float[] acc;
    private boolean[] seen;

    final IntList frontF = new IntList();
    final IntList frontB = new IntList();
    final IntList spare = new IntList();
    final IntList touched = new IntList();

    Workspace(int n) {
        this.n = n;
    }

    byte[] depthF() {
        if (depthF == null) depthF = new byte[n];
        Arrays.fill(depthF, (byte) -1);
        return depthF;
    }

    byte[] depthB() {
        if (depthB == null) depthB = new byte[n];
        Arrays.fill(depthB, (byte) -1);
        return depthB;
    }

    int[] parentF() {
        if (parentF == null) parentF = new int[n];
        return parentF;
    }

    int[] parentB() {
        if (parentB == null) parentB = new int[n];
        return parentB;
    }

    float[] accCleared() {
        if (acc == null) {
            acc = new float[n];
            seen = new boolean[n];
        } else {
            for (int i = 0; i < touched.len; i++) {
                int v = touched.a[i];
                acc[v] = 0f;
                seen[v] = false;
            }
        }
        touched.len = 0;
        return acc;
    }

    boolean[] seen() {
        return seen;
    }

    static final class IntList {
        int[] a = new int[1024];
        int len;

        void add(int v) {
            if (len == a.length) a = Arrays.copyOf(a, len * 2);
            a[len++] = v;
        }
    }
}
