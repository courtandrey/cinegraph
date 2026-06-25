package com.github.courtandrey.cinegraph.exporter.ingest;

import com.github.courtandrey.cinegraph.exporter.repo.PgSupport;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.MOVIE;

@Service
public class PathIndexService {

    private static final Logger log = LoggerFactory.getLogger(PathIndexService.class);

    private final DSLContext ctx;

    public PathIndexService(DSLContext ctx) {
        this.ctx = ctx;
    }

    public void recompute() {
        long start = System.currentTimeMillis();
        PgSupport.computeDegrees(ctx);
        PgSupport.analyze(ctx, MOVIE);
        log.info("[path-index] movie.degree recomputed in {} ms", System.currentTimeMillis() - start);
    }
}
