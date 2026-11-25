package com.github.courtandrey.cinegraph.api.letterboxd;

import io.vavr.control.Try;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class LetterboxdUris {

    private LetterboxdUris() {}

    private static final Set<String> HOSTS = Set.of("letterboxd.com", "www.letterboxd.com", "boxd.it");
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    public static Optional<URI> filmUri(String raw) {
        return Try.of(() -> new URI(raw)).toJavaOptional().filter(LetterboxdUris::isLetterboxd);
    }

    public static Optional<URI> redirectTarget(URI from, String location) {
        return Try.of(() -> from.resolve(location)).toJavaOptional().filter(LetterboxdUris::isLetterboxd);
    }

    public static boolean isRedirect(int statusCode) {
        return REDIRECTS.contains(statusCode);
    }

    private static boolean isLetterboxd(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getHost() != null
                && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }
}
