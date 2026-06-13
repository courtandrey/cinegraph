package com.github.courtandrey.cinegraph.api.dto;

import java.util.List;

public record GraphPayload(MovieDetail center, List<GraphNode> nodes, List<GraphEdge> edges) {}
