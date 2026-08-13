package com.github.courtandrey.cinegraph.api.letterboxd;

import com.github.courtandrey.cinegraph.api.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Slf4j
public class LetterboxdTmdbResolver {

    private final LetterboxdClient client;

    public LetterboxdTmdbResolver(LetterboxdClient client) {
        this.client = client;
    }

    @Cacheable(CacheConfig.LETTERBOXD_TMDB_ID)
    public Optional<Long> tmdbId(String filmUri) {
        return client.filmPage(filmUri)
                .flatMap(LetterboxdParsing::parseTmdbId)
                .or(() -> {
                    log.error("Could not parse tmdb id from {}", filmUri);
                    return Optional.empty();
                });
    }
}
