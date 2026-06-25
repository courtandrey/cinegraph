package com.github.courtandrey.cinegraph.engine.dto;

import java.util.List;

public record PathResult(boolean found, String reason, int hops,
                         List<GraphNode> nodes, List<GraphEdge> edges) {}
