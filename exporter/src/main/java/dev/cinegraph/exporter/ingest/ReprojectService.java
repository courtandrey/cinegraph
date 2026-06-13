package dev.cinegraph.exporter.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cinegraph.exporter.admin.RunRegistry;
import dev.cinegraph.exporter.domain.RunKind;
import dev.cinegraph.exporter.domain.RunStatus;
import dev.cinegraph.exporter.repo.LoadRunRepository;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Result;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static dev.cinegraph.exporter.jooq.Tables.MOVIE_RAW;

/**
 * Re-projects every stored raw TMDB payload through the current projection rules
 * (role normalization included) without re-fetching. Use after changing roles.yml
 * or the projection logic to migrate previously ingested movies.
 */
@Service
public class ReprojectService {

    private static final Logger log = LoggerFactory.getLogger(ReprojectService.class);
    private static final int BATCH_SIZE = 500;
    private static final int STATS_EVERY_BATCHES = 20;

    private final DSLContext ctx;
    private final LoadRunRepository runRepo;
    private final RunRegistry runRegistry;
    private final ProjectionWriter writer;
    private final Roles roles;
    private final ObjectMapper mapper;

    public ReprojectService(DSLContext ctx, LoadRunRepository runRepo, RunRegistry runRegistry,
                            ProjectionWriter writer, Roles roles, ObjectMapper mapper) {
        this.ctx = ctx;
        this.runRepo = runRepo;
        this.runRegistry = runRegistry;
        this.writer = writer;
        this.roles = roles;
        this.mapper = mapper;
    }

    public long trigger() {
        if (runRepo.hasRunning(RunKind.FULL, RunKind.INCREMENTAL, RunKind.RETRY, RunKind.REPROJECT)) {
            throw new IllegalStateException("Another load is already running");
        }
        long runId = runRepo.create(RunKind.REPROJECT);
        runRegistry.launch(runId, () -> run(runId));
        return runId;
    }

    private void run(long runId) {
        log.info("[run {}] Reprojection of movie_raw started", runId);
        long reprojected = 0;
        long failed = 0;
        long lastId = 0;
        long batches = 0;
        try {
            while (!runRegistry.isCancelled(runId)) {
                Result<Record2<Long, JSONB>> rows = ctx
                        .select(MOVIE_RAW.MOVIE_ID, MOVIE_RAW.PAYLOAD)
                        .from(MOVIE_RAW)
                        .where(MOVIE_RAW.MOVIE_ID.gt(lastId))
                        .orderBy(MOVIE_RAW.MOVIE_ID)
                        .limit(BATCH_SIZE)
                        .fetch();
                if (rows.isEmpty()) break;
                lastId = rows.get(rows.size() - 1).value1();

                List<Projections.Fetched> fetched = rows.stream()
                        .map(r -> new Projections.Fetched(r.value1(), r.value2().data()))
                        .toList();
                Projections.Batch batch = Projections.project(fetched, roles, mapper);
                writer.write(batch, null);

                reprojected += batch.projectedIds().size();
                failed += batch.failures().size();
                if (++batches % STATS_EVERY_BATCHES == 0) {
                    runRepo.updateStats(runId, statsJson(reprojected, failed, lastId));
                }
            }

            RunStatus status = runRegistry.isCancelled(runId) ? RunStatus.CANCELLED : RunStatus.COMPLETED;
            runRepo.finish(runId, status, statsJson(reprojected, failed, lastId));
            log.info("[run {}] Reprojection {}; {} movies, {} failures",
                    runId, status, reprojected, failed);
        } catch (Exception e) {
            log.error("[run {}] Reprojection failed: {}", runId, e.getMessage(), e);
            runRepo.finish(runId, RunStatus.FAILED, statsJson(reprojected, failed, lastId));
        }
    }

    private static String statsJson(long reprojected, long failed, long lastId) {
        return "{\"reprojected\":" + reprojected
                + ",\"failed\":" + failed
                + ",\"last_movie_id\":" + lastId + "}";
    }
}
