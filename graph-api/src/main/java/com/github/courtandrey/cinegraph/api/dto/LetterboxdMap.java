package com.github.courtandrey.cinegraph.api.dto;

import java.util.List;

public record LetterboxdMap(List<GraphNode> nodes, List<MapEdge> edges, List<MapFilm> films,
                            List<MapCluster> clusters, List<ClusterLink> links) {}
