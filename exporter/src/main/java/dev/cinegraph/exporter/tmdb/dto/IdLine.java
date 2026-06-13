package dev.cinegraph.exporter.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IdLine(
        long id,
        boolean adult,
        boolean video,
        @JsonProperty("original_title") String originalTitle,
        double popularity
) {}
