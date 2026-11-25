package com.github.courtandrey.cinegraph.api.letterboxd;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class LetterboxdUrisTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://boxd.it/2bg8",
            "https://letterboxd.com/film/parasite-2019/",
            "https://www.letterboxd.com/film/parasite-2019/",
            "HTTPS://Letterboxd.com/film/parasite-2019/",
            "https://letterboxd.com:443/film/parasite-2019/"})
    void filmUri_acceptsLetterboxdHttpsLinks(String raw) {
        assertThat(LetterboxdUris.filmUri(raw)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://boxd.it/2bg8",
            "http://postgres:5432",
            "http://graph-engine:8085",
            "http://169.254.169.254/latest/meta-data/",
            "https://letterboxd.com.evil.example/film/x/",
            "https://evil.example/letterboxd.com",
            "https://letterboxd.com@evil.example/",
            "https://user@letterboxd.com/film/x/",
            "https://letterboxd.com:8443/film/x/",
            "https://letterboxd.com./film/x/",
            "file:///etc/passwd",
            "/film/x/",
            "not a uri",
            ""})
    void filmUri_rejectsAnythingElse(String raw) {
        assertThat(LetterboxdUris.filmUri(raw)).isEmpty();
    }

    @Test
    void redirectTarget_followsBoxdItToLetterboxd() {
        URI from = URI.create("https://boxd.it/2bg8");
        assertThat(LetterboxdUris.redirectTarget(from, "https://letterboxd.com/film/parasite-2019/"))
                .contains(URI.create("https://letterboxd.com/film/parasite-2019/"));
    }

    @Test
    void redirectTarget_resolvesRelativeLocationAgainstCurrentHop() {
        URI from = URI.create("https://letterboxd.com/film/parasite/");
        assertThat(LetterboxdUris.redirectTarget(from, "/film/parasite-2019/"))
                .contains(URI.create("https://letterboxd.com/film/parasite-2019/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://letterboxd.com/film/x/",
            "http://169.254.169.254/",
            "https://evil.example/",
            "//evil.example/film/x/",
            "bad location"})
    void redirectTarget_rejectsHopsLeavingLetterboxd(String location) {
        assertThat(LetterboxdUris.redirectTarget(URI.create("https://boxd.it/2bg8"), location)).isEmpty();
    }
}
