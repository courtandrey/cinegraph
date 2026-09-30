package com.github.courtandrey.cinegraph.api.letterboxd;

import com.github.courtandrey.cinegraph.api.config.CacheConfig;
import com.github.courtandrey.cinegraph.api.domain.UriResolution;
import com.github.courtandrey.cinegraph.api.repo.LetterboxdUriRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;


@Component
@Slf4j
public class LetterboxdTmdbResolver {

    private final LetterboxdClient client;
    private final LetterboxdUriRepository uriRepo;

    public LetterboxdTmdbResolver(LetterboxdClient client, LetterboxdUriRepository uriRepo) {
        this.client = client;
        this.uriRepo = uriRepo;
    }

    @Cacheable(value = CacheConfig.LETTERBOXD_TMDB_ID, unless = "#result.retryable()")
    public UriResolution resolve(String filmUri) {
        return uriRepo.find(filmUri).orElseGet(() -> fetch(filmUri));
    }

    private UriResolution fetch(String filmUri) {
        return client.filmPage(filmUri)
                .map(page -> page.map(html -> fromHtml(filmUri, html)).orElseGet(() -> gone(filmUri)))
                .onSuccess(resolution -> uriRepo.save(filmUri, resolution))
                .onFailure(e -> log.warn("Could not read letterboxd page {}: {}", filmUri, e.getMessage()))
                .getOrElse(UriResolution.TRANSIENT_FAILURE);
    }

    private static UriResolution fromHtml(String filmUri, String html) {
        return LetterboxdParsing.parseTmdbId(html)
                .map(UriResolution::resolved)
                .orElseGet(() -> {
                    log.info("No TMDB movie behind {} — linked instead to {}", filmUri,
                            LetterboxdParsing.tmdbAnchor(html).orElse("nothing on TMDB"));
                    return UriResolution.NO_MOVIE;
                });
    }

    private static UriResolution gone(String filmUri) {
        log.info("Letterboxd no longer serves {}", filmUri);
        return UriResolution.NO_MOVIE;
    }
}
