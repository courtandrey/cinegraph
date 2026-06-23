package com.github.courtandrey.cinegraph.api.dto;

import java.util.List;

public record PathResult(boolean found, String reason, int hops,
                         List<GraphNode> nodes, List<GraphEdge> edges) {}
