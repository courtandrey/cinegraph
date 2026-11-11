package com.github.courtandrey.cinegraph.api.dto;

import com.github.courtandrey.cinegraph.api.domain.ClusterKind;

import java.util.List;

public record MapCluster(long id, ClusterKind kind, long componentId, long hubId, int size,
                         double x, double y, double radius,
                         List<GenreShare> genres, List<Contributor> contributors) {

    public record GenreShare(int genreId, String name, int films) {}

    public record Contributor(long personId, String name, String role, int films, double score) {}
}
