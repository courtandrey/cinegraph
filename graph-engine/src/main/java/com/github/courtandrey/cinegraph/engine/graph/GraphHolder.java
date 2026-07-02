package com.github.courtandrey.cinegraph.engine.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class GraphHolder {

    public enum Status { LOADING, READY, FAILED }

    private static final Logger log = LoggerFactory.getLogger(GraphHolder.class);

    private final GraphLoader loader;
    private final boolean loadOnStartup;
    private final Path snapshotPath;

    private final AtomicReference<ImmutableGraph> ref = new AtomicReference<>();
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private volatile Status status = Status.LOADING;

    public GraphHolder(GraphLoader loader,
                       @Value("${engine.load-on-startup:true}") boolean loadOnStartup,
                       @Value("${engine.snapshot-path:data/graph-snapshot.bin}") String snapshotPath) {
        this.loader = loader;
        this.loadOnStartup = loadOnStartup;
        this.snapshotPath = Path.of(snapshotPath);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (loadOnStartup) {
            startInitialLoad();
        }
    }

    public boolean startInitialLoad() {
        return run("engine-graph-load", () -> GraphSnapshot.read(snapshotPath)
                .map(g -> {
                    log.info("[engine] restored graph from snapshot {}: {} nodes, {} edges",
                            snapshotPath, g.nodeCount(), g.edgeCount());
                    return g;
                })
                .orElseGet(this::computeAndSnapshot));
    }

    public boolean triggerReload() {
        return run("engine-graph-reload", this::computeAndSnapshot);
    }

    private ImmutableGraph computeAndSnapshot() {
        GraphSnapshot.delete(snapshotPath);
        ImmutableGraph graph = loader.load();
        try {
            GraphSnapshot.write(snapshotPath, graph);
            log.info("[engine] wrote graph snapshot to {}", snapshotPath);
        } catch (IOException e) {
            log.error("[engine] failed to write snapshot {}", snapshotPath, e);
        }
        return graph;
    }

    private boolean run(String threadName, Callable<ImmutableGraph> task) {
        if (!loading.compareAndSet(false, true)) {
            return false;
        }
        ref.set(null);
        status = Status.LOADING;
        Thread.ofPlatform().name(threadName).start(() -> {
            try {
                ref.set(task.call());
                status = Status.READY;
            } catch (Exception e) {
                log.error("[engine] graph load failed", e);
                status = Status.FAILED;
            } finally {
                loading.set(false);
            }
        });
        return true;
    }

    public ImmutableGraph graph() {
        return ref.get();
    }

    public boolean isReady() {
        return status == Status.READY && ref.get() != null;
    }

    public Status status() {
        return status;
    }
}
