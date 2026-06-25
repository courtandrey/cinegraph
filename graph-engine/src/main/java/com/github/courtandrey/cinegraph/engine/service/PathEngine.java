package com.github.courtandrey.cinegraph.engine.service;

import com.github.courtandrey.cinegraph.engine.dto.GraphEdge;
import com.github.courtandrey.cinegraph.engine.dto.GraphNode;
import com.github.courtandrey.cinegraph.engine.dto.PathResult;
import com.github.courtandrey.cinegraph.engine.graph.GraphHolder;
import com.github.courtandrey.cinegraph.engine.graph.ImmutableGraph;
import com.github.courtandrey.cinegraph.engine.repo.HydrationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PathEngine {

    private final GraphHolder holder;
    private final HydrationRepository hydration;
    private final ReasonResolver reasons;
    private final int maxHops;

    public PathEngine(GraphHolder holder, HydrationRepository hydration, ReasonResolver reasons,
                      @Value("${engine.max-hops:20}") int maxHops) {
        this.holder = holder;
        this.hydration = hydration;
        this.reasons = reasons;
        this.maxHops = maxHops;
    }

    public PathResult shortestPath(long from, long to) {
        ImmutableGraph g = holder.graph();
        int fromIdx = g.indexOf(from);
        int toIdx = g.indexOf(to);

        if (fromIdx < 0 || toIdx < 0) {
            boolean fromOk = fromIdx >= 0 || hydration.exists(from);
            boolean toOk = toIdx >= 0 || hydration.exists(to);
            if (!fromOk || !toOk) return fail("not_found");
            if (from == to) return buildPath(new long[]{from});
            return fail("not_connected");
        }
        if (from == to) return buildPath(new long[]{from});
        if (!g.sameComponent(fromIdx, toIdx)) return fail("not_connected");

        long[] path = g.shortestPath(fromIdx, toIdx, maxHops);
        return path == null ? fail("unreachable") : buildPath(path);
    }

    private PathResult buildPath(long[] pathIds) {
        List<Long> ids = Arrays.stream(pathIds).boxed().toList();
        Map<Long, GraphNode> byId = hydration.findNodes(ids).stream()
                .collect(Collectors.toMap(GraphNode::id, Function.identity()));
        List<GraphNode> nodes = ids.stream().map(byId::get).filter(Objects::nonNull).toList();

        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i + 1 < pathIds.length; i++) {
            long s = pathIds[i];
            long t = pathIds[i + 1];
            hydration.findEdge(s, t).ifPresent(e -> edges.add(new GraphEdge(
                    s, t, e.totalScore() == null ? 0f : e.totalScore(),
                    reasons.resolveCrewPerson(e.components()), e.components())));
        }
        return new PathResult(true, null, Math.max(0, pathIds.length - 1), nodes, edges);
    }

    private PathResult fail(String reason) {
        return new PathResult(false, reason, 0, List.of(), List.of());
    }
}
