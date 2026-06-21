package com.github.courtandrey.cinegraph.api.dto;

public record GraphNode(long id, String title, Integer year, String posterPath, double inScore) {

    public GraphNode withInScore(double score) {
        return new GraphNode(id, title, year, posterPath, score);
    }
}
