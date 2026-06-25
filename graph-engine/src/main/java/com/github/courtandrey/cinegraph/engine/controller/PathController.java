package com.github.courtandrey.cinegraph.engine.controller;

import com.github.courtandrey.cinegraph.engine.graph.GraphHolder;
import com.github.courtandrey.cinegraph.engine.graph.ImmutableGraph;
import com.github.courtandrey.cinegraph.engine.service.PathEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class PathController {

    private final GraphHolder holder;
    private final PathEngine engine;

    public PathController(GraphHolder holder, PathEngine engine) {
        this.holder = holder;
        this.engine = engine;
    }

    @GetMapping("/api/movies/{from}/path/{to}")
    public ResponseEntity<?> path(@PathVariable long from, @PathVariable long to) {
        if (!holder.isReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", holder.status().name(), "message", "graph not loaded yet"));
        }
        return ResponseEntity.ok(engine.shortestPath(from, to));
    }

    @GetMapping("/api/engine/status")
    public ResponseEntity<?> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", holder.status().name());
        body.put("ready", holder.isReady());
        ImmutableGraph g = holder.graph();
        if (g != null) {
            body.put("nodes", g.nodeCount());
            body.put("edges", g.edgeCount());
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/engine/reload")
    public ResponseEntity<?> reload() {
        boolean started = holder.triggerReload();
        return ResponseEntity.accepted()
                .body(Map.of("reloadStarted", started, "status", holder.status().name()));
    }
}
