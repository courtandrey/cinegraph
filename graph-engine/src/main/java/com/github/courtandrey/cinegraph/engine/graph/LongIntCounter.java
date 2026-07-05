package com.github.courtandrey.cinegraph.engine.graph;

import java.util.Arrays;

final class LongIntCounter {

    private long[] keys;
    private int[] counts;
    private int mask;
    private int size;

    LongIntCounter(int expectedKeys) {
        int cap = Integer.highestOneBit(Math.max(16, expectedKeys) - 1) << 2;
        keys = new long[cap];
        counts = new int[cap];
        mask = cap - 1;
    }

    void increment(long key) {
        if ((size + 1) * 2 > keys.length) grow();
        int i = slot(keys, counts, mask, key);
        if (counts[i] == 0) {
            keys[i] = key;
            size++;
        }
        counts[i]++;
    }

    int get(long key) {
        return counts[slot(keys, counts, mask, key)];
    }

    int size() {
        return size;
    }

    long[] sortedKeys() {
        long[] out = new long[size];
        int j = 0;
        for (int i = 0; i < keys.length; i++) {
            if (counts[i] != 0) out[j++] = keys[i];
        }
        Arrays.sort(out);
        return out;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldCounts = counts;
        int cap = oldKeys.length << 1;
        long[] newKeys = new long[cap];
        int[] newCounts = new int[cap];
        int newMask = cap - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldCounts[i] == 0) continue;
            int s = slot(newKeys, newCounts, newMask, oldKeys[i]);
            newKeys[s] = oldKeys[i];
            newCounts[s] = oldCounts[i];
        }
        keys = newKeys;
        counts = newCounts;
        mask = newMask;
    }

    private static int slot(long[] keys, int[] counts, int mask, long key) {
        int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> 32) & mask;
        while (counts[i] != 0 && keys[i] != key) {
            i = (i + 1) & mask;
        }
        return i;
    }
}
