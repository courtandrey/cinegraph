package com.github.courtandrey.cinegraph.api.controller;

import com.github.courtandrey.cinegraph.api.config.SitemapProperties;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository.SitemapEntry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RestController
public class SitemapController {

    private static final Duration TTL = Duration.ofHours(24);

    private final MovieQueryRepository movieRepo;
    private final SitemapProperties props;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    private record Cached(String xml, Instant builtAt) {}

    public SitemapController(MovieQueryRepository movieRepo, SitemapProperties props) {
        this.movieRepo = movieRepo;
        this.props = props;
    }

    @GetMapping(value = "/api/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemap() {
        Cached cached = cache.get();
        if (cached != null && cached.builtAt().plus(TTL).isAfter(Instant.now())) return cached.xml();
        String xml = buildXml(props.getSiteBaseUrl(), movieRepo.findSitemapEntries(props.getMaxUrls()));
        cache.set(new Cached(xml, Instant.now()));
        return xml;
    }

    static String buildXml(String base, List<SitemapEntry> entries) {
        StringBuilder sb = new StringBuilder(entries.size() * 96 + 256);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
          .append("<url><loc>").append(base).append("/</loc></url>\n");
        entries.forEach(e -> sb
                .append("<url><loc>").append(base).append("/film/").append(e.movieId())
                .append("</loc><lastmod>").append(e.updatedAt().toLocalDate())
                .append("</lastmod></url>\n"));
        return sb.append("</urlset>\n").toString();
    }
}
