package com.github.courtandrey.cinegraph.api.domain;

import java.util.Optional;

import static com.github.courtandrey.cinegraph.api.domain.LetterboxdUriOutcome.RESOLVED;
import static com.github.courtandrey.cinegraph.api.domain.LetterboxdUriOutcome.TRANSIENT;
import static com.github.courtandrey.cinegraph.api.domain.LetterboxdUriOutcome.UNRESOLVABLE;

public record UriResolution(LetterboxdUriOutcome outcome, Long tmdbId) {

    public static final UriResolution TRANSIENT_FAILURE = new UriResolution(TRANSIENT, null);
    public static final UriResolution NO_MOVIE = new UriResolution(UNRESOLVABLE, null);

    public static UriResolution resolved(long tmdbId) {
        return new UriResolution(RESOLVED, tmdbId);
    }

    public Optional<Long> movieId() {
        return Optional.ofNullable(tmdbId);
    }

    public boolean retryable() {
        return outcome == TRANSIENT;
    }
}
