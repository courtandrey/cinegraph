package com.github.courtandrey.cinegraph.exporter.ingest;

import com.github.courtandrey.cinegraph.exporter.repo.PgSupport;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.MOVIE;

@Service
public class ComponentBuildService {

    private static final Logger log = LoggerFactory.getLogger(ComponentBuildService.class);
    private static final int MAX_ITERATIONS = 64;

    private final DSLContext ctx;

    public ComponentBuildService(DSLContext ctx) {
        this.ctx = ctx;
    }

    public void recompute() {
        long start = System.currentTimeMillis();
        ctx.update(MOVIE).set(MOVIE.COMPONENT_ID, MOVIE.MOVIE_ID).execute();

        int iteration = 0;
        int changed;
        do {
            changed = PgSupport.propagateComponents(ctx);
            iteration++;
            log.info("[components] pass {} relabelled {} movies", iteration, changed);
        } while (changed > 0 && iteration < MAX_ITERATIONS);

        PgSupport.analyze(ctx, MOVIE);
        log.info("[components] done in {} passes, {} ms", iteration, System.currentTimeMillis() - start);
    }
}
