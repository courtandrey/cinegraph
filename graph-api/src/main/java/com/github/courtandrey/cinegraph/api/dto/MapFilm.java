package com.github.courtandrey.cinegraph.api.dto;

import com.github.courtandrey.cinegraph.api.domain.FilmPlacement;

import java.util.List;

public record MapFilm(long movieId, long clusterId, long componentId, FilmPlacement placement,
                      double cohesion, int linksInside, int linksTotal, List<ClusterShare> shares,
                      double x, double y) {

    public record ClusterShare(long clusterId, double share, int links) {}
}
