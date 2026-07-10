package com.github.courtandrey.cinegraph.engine.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphHolderTest {

    @TempDir
    Path dir;

    private static ImmutableGraph graphOf(int nodes) {
        long[] ids = new long[nodes];
        for (int i = 0; i < nodes; i++) ids[i] = i + 1;
        return new ImmutableGraph(ids, new int[nodes + 1],
                IntBuffer.wrap(new int[0]), FloatBuffer.wrap(new float[0]));
    }

    private GraphHolder holderWith(GraphLoader loader) {
        return new GraphHolder(loader, false, dir.resolve("graph.bin").toString());
    }

    @Test
    void reload_servesOldGraphUntilNewOneIsReady() throws Exception {
        GraphLoader loader = mock(GraphLoader.class);
        ImmutableGraph first = graphOf(1);
        ImmutableGraph second = graphOf(2);
        CountDownLatch release = new CountDownLatch(1);
        when(loader.load(any())).thenReturn(first).thenAnswer(inv -> {
            release.await();
            return second;
        });

        GraphHolder holder = holderWith(loader);
        holder.triggerReload();
        await(() -> holder.graph() == first);
        assertEquals("READY", holder.phase());

        holder.triggerReload();
        assertEquals("RELOADING", holder.phase());
        assertTrue(holder.isReady());
        assertSame(first, holder.graph());

        release.countDown();
        await(() -> holder.graph() == second);
        assertTrue(holder.isReady());
        assertEquals("READY", holder.phase());
    }

    @Test
    void failedReload_keepsPreviousGraph() throws Exception {
        GraphLoader loader = mock(GraphLoader.class);
        ImmutableGraph first = graphOf(1);
        when(loader.load(any())).thenReturn(first).thenThrow(new IllegalStateException("db down"));

        GraphHolder holder = holderWith(loader);
        holder.triggerReload();
        await(() -> holder.graph() == first);

        holder.triggerReload();
        await(() -> "READY".equals(holder.phase()));
        assertSame(first, holder.graph());
        assertTrue(holder.isReady());
    }

    @Test
    void failedInitialLoad_reportsFailed() throws Exception {
        GraphLoader loader = mock(GraphLoader.class);
        when(loader.load(any())).thenThrow(new IllegalStateException("db down"));

        GraphHolder holder = holderWith(loader);
        holder.triggerReload();
        await(() -> holder.status() == GraphHolder.Status.FAILED);
        assertEquals("FAILED", holder.phase());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition not met in 5s");
            Thread.sleep(10);
        }
    }
}
