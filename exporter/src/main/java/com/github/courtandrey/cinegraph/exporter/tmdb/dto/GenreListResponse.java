package com.github.courtandrey.cinegraph.exporter.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenreListResponse(List<GenreDto> genres) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenreDto(int id, String name) {}
}
