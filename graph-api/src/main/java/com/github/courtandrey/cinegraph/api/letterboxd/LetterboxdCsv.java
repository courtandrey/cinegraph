package com.github.courtandrey.cinegraph.api.letterboxd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import io.vavr.control.Try;

import java.util.List;

public final class LetterboxdCsv {

    private LetterboxdCsv() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilmRow(
            @JsonProperty("Name") String name,
            @JsonProperty("Year") Integer year,
            @JsonProperty("Letterboxd URI") String uri,
            @JsonProperty("Rating") Double rating) {}

    private static final CsvMapper CSV = CsvMapper.builder()
            .enable(CsvParser.Feature.TRIM_SPACES)
            .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
            .build();

    private static final CsvSchema SCHEMA = CsvSchema.emptySchema().withHeader();

    public static List<FilmRow> parse(String content) {
        if (content == null || content.isBlank()) return List.of();
        return Try.of(() -> CSV.readerFor(FilmRow.class).with(SCHEMA)
                        .<FilmRow>readValues(content).readAll())
                .map(rows -> rows.stream()
                        .filter(r -> r.name() != null && !r.name().isBlank())
                        .toList())
                .getOrElse(List.of());
    }
}
