package com.github.courtandrey.cinegraph.engine.health;

import com.github.courtandrey.cinegraph.engine.graph.GraphHolder;
import com.github.courtandrey.cinegraph.engine.graph.ImmutableGraph;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class GraphHealthIndicator implements HealthIndicator {

    private final GraphHolder holder;

    public GraphHealthIndicator(GraphHolder holder) {
        this.holder = holder;
    }

    @Override
    public Health health() {
        Health.Builder builder = switch (holder.status()) {
            case READY -> Health.up();
            case LOADING -> Health.status("LOADING");
            case FAILED -> Health.down();
        };
        ImmutableGraph graph = holder.graph();
        if (graph != null) {
            builder.withDetail("nodes", graph.nodeCount()).withDetail("edges", graph.edgeCount());
        }
        return builder.withDetail("state", holder.phase()).build();
    }
}
