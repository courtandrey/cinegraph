package com.github.courtandrey.cinegraph.api.letterboxd;

import com.github.courtandrey.cinegraph.api.config.LetterboxdProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LetterboxdClientTest {

    private final AtomicInteger hits = new AtomicInteger();
    private HttpServer internal;

    @BeforeEach
    void startInternalServer() throws IOException {
        internal = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        internal.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        internal.start();
    }

    @AfterEach
    void stopInternalServer() {
        internal.stop(0);
    }

    @Test
    void filmPage_neverRequestsNonLetterboxdHosts() {
        String uri = "http://127.0.0.1:" + internal.getAddress().getPort() + "/";

        var result = new LetterboxdClient(new LetterboxdProperties()).filmPage(uri);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getCause()).isInstanceOf(LetterboxdClient.ForeignUriException.class);
        assertThat(hits.get()).isZero();
    }
}
