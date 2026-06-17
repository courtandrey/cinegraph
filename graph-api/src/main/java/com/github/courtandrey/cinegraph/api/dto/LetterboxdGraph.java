package com.github.courtandrey.cinegraph.api.dto;

import java.util.List;

public record LetterboxdGraph(long centerId, List<GraphNode> nodes, List<GraphEdge> edges) {}
