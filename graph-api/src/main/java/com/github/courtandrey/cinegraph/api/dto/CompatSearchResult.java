package com.github.courtandrey.cinegraph.api.dto;

public record CompatSearchResult(long id, String title, Integer year,
                                 String posterPath, boolean fromSet) {}
