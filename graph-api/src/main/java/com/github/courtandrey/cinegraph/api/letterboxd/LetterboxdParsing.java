package com.github.courtandrey.cinegraph.api.letterboxd;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LetterboxdParsing {

    private LetterboxdParsing() {}

    private static final Pattern TMDB_ANCHOR = Pattern.compile("<a[^>]*data-track-action=\"TMDB\"[^>]*>");
    private static final Pattern TMDB_MOVIE_ID = Pattern.compile("themoviedb\\.org/movie/(\\d+)");

    public static Optional<Long> parseTmdbId(String filmPageHtml) {
        return Optional.ofNullable(filmPageHtml)
                .map(TMDB_ANCHOR::matcher)
                .filter(Matcher::find)
                .map(Matcher::group)
                .map(TMDB_MOVIE_ID::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .map(Long::parseLong);
    }
}
