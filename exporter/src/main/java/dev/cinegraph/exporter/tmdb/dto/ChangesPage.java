package dev.cinegraph.exporter.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChangesPage(
        List<ChangeEntry> results,
        int page,
        @JsonProperty("total_pages") int totalPages,
        @JsonProperty("total_results") int totalResults
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangeEntry(long id, boolean adult) {}
}
