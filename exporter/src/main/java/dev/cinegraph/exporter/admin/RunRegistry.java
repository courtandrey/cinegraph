package dev.cinegraph.exporter.admin;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RunRegistry {

    private final ConcurrentHashMap<Long, AtomicBoolean> active = new ConcurrentHashMap<>();

    public void launch(long runId, Runnable job) {
        active.put(runId, new AtomicBoolean(false));
        Thread.ofVirtual().name("run-" + runId).start(() -> {
            try {
                job.run();
            } finally {
                active.remove(runId);
            }
        });
    }

    public boolean cancel(long runId) {
        AtomicBoolean flag = active.get(runId);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }

    public boolean isCancelled(long runId) {
        AtomicBoolean flag = active.get(runId);
        return flag != null && flag.get();
    }
}
