package com.github.courtandrey.cinegraph.engine.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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

    public void startInitialLoad() {
        run("engine-graph-load", () -> GraphSnapshot.read(snapshotPath)
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
        return loader.load(snapshotPath);
    }

    private boolean run(String threadName, Callable<ImmutableGraph> task) {
        if (!loading.compareAndSet(false, true)) {
            return false;
        }
        if (ref.get() == null) {
            status = Status.LOADING;
        }
        Thread.ofPlatform().name(threadName).start(() -> {
            try {
                ref.set(task.call());
                status = Status.READY;
            } catch (Exception e) {
                ImmutableGraph previous = ref.get();
                if (previous == null) {
                    log.error("[engine] graph load failed", e);
                    status = Status.FAILED;
                } else {
                    log.error("[engine] graph reload failed; keeping previous graph ({} nodes, {} edges)",
                            previous.nodeCount(), previous.edgeCount(), e);
                }
            } finally {
                loading.set(false);
            }
        });
        return true;
    }

    public String phase() {
        return loading.get() && ref.get() != null ? "RELOADING" : status.name();
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
