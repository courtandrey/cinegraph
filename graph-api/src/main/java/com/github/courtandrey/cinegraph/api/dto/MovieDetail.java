package com.github.courtandrey.cinegraph.api.dto;

import java.time.LocalDate;
import java.util.List;

public record MovieDetail(
        long id,
        String title,
        String originalTitle,
        Integer year,
        LocalDate releaseDate,
        String overview,
        Integer runtime,
        String posterPath,
        Double voteAverage,
        List<String> countries,
        List<Genre> genres
) {
    public record Genre(int id, String name) {}
}
