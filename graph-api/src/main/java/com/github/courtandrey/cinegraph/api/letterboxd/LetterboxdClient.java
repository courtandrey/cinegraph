package com.github.courtandrey.cinegraph.api.letterboxd;

import com.github.courtandrey.cinegraph.api.config.LetterboxdProperties;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Component
@Slf4j
public class LetterboxdClient {

    private final HttpClient http;
    private final LetterboxdProperties props;

    public LetterboxdClient(LetterboxdProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Optional<String> filmPage(String uri) {
        return Try.of(() -> http.send(request(uri), HttpResponse.BodyHandlers.ofString()))
                .andThen(resp -> log.debug("Response from letterboxd {}", resp.statusCode()))
                .filter(r -> r.statusCode() == 200)
                .map(HttpResponse::body)
                .toJavaOptional();
    }

    private HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", props.getUserAgent())
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
    }
}
