package com.github.courtandrey.cinegraph.engine.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class GraphHolder {

    public enum Status { LOADING, READY, FAILED }

    private static final Logger log = LoggerFactory.getLogger(GraphHolder.class);

    private final GraphLoader loader;
    private final boolean loadOnStartup;

    private final AtomicReference<ImmutableGraph> ref = new AtomicReference<>();
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private volatile Status status = Status.LOADING;

    public GraphHolder(GraphLoader loader, @Value("${engine.load-on-startup:true}") boolean loadOnStartup) {
        this.loader = loader;
        this.loadOnStartup = loadOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (loadOnStartup) {
            triggerReload();
        }
    }

    public boolean triggerReload() {
        if (!loading.compareAndSet(false, true)) {
            return false;
        }
        ref.set(null);
        status = Status.LOADING;
        Thread.ofPlatform().name("engine-graph-load").start(() -> {
            try {
                ImmutableGraph graph = loader.load();
                ref.set(graph);
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
