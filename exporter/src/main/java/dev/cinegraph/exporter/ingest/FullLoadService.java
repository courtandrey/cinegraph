package dev.cinegraph.exporter.ingest;

import dev.cinegraph.exporter.admin.RunRegistry;
import dev.cinegraph.exporter.domain.RunKind;
import dev.cinegraph.exporter.domain.RunStatus;
import dev.cinegraph.exporter.repo.FetchQueueRepository;
import dev.cinegraph.exporter.repo.LoadRunRepository;
import dev.cinegraph.exporter.repo.SyncStateRepository;
import dev.cinegraph.exporter.tmdb.TmdbClient;
import dev.cinegraph.exporter.tmdb.TmdbClient.ExportNotFoundException;
import dev.cinegraph.exporter.tmdb.dto.IdLine;
import io.vavr.control.Option;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static dev.cinegraph.exporter.jooq.Tables.GENRE;

@Service
public class FullLoadService {

    private static final Logger log = LoggerFactory.getLogger(FullLoadService.class);
    private static final int SEED_BATCH_SIZE = 500;
    private static final int EXPORT_FALLBACK_DAYS = 3;

    private final TmdbClient tmdb;
    private final FetchQueueRepository queueRepo;
    private final LoadRunRepository runRepo;
    private final FetchPipeline pipeline;
    private final RunRegistry runRegistry;
    private final EdgeBuildService edgeBuildService;
    private final SyncStateRepository syncState;
    private final DSLContext ctx;

    @Value("${ingest.skip-adult:true}") private boolean skipAdult;
    @Value("${ingest.skip-video:true}") private boolean skipVideo;
    @Value("${ingest.limit-ids:0}") private long limitIds;

    public FullLoadService(TmdbClient tmdb, FetchQueueRepository queueRepo,
                           LoadRunRepository runRepo, FetchPipeline pipeline,
                           RunRegistry runRegistry, EdgeBuildService edgeBuildService,
                           SyncStateRepository syncState, DSLContext ctx) {
        this.tmdb = tmdb;
        this.queueRepo = queueRepo;
        this.runRepo = runRepo;
        this.pipeline = pipeline;
        this.runRegistry = runRegistry;
        this.edgeBuildService = edgeBuildService;
        this.syncState = syncState;
        this.ctx = ctx;
    }

    public long triggerFullLoad() {
        if (runRepo.hasRunning(RunKind.FULL)) {
            throw new IllegalStateException("A FULL load is already running");
        }
        long runId = runRepo.create(RunKind.FULL);
        runRegistry.launch(runId, () -> runFullLoad(runId));
        return runId;
    }

    public long triggerRetryStuck() {
        if (runRepo.hasRunning(RunKind.FULL, RunKind.INCREMENTAL, RunKind.RETRY, RunKind.REPROJECT)) {
            throw new IllegalStateException("Another load is already running");
        }
        long runId = runRepo.create(RunKind.RETRY);
        runRegistry.launch(runId, () -> runRetryStuck(runId));
        return runId;
    }

    private void runFullLoad(long runId) {
        log.info("[run {}] Full load started", runId);
        try {
            Option<LocalDate> exportDate = stageA(runId);
            if (runRegistry.isCancelled(runId)) {
                runRepo.finish(runId, RunStatus.CANCELLED, "{}");
                return;
            }

            FetchPipeline.StatsSnapshot stats = pipeline.execute(runId, FetchPipeline.Options.fullLoad());
            if (runRegistry.isCancelled(runId)) {
                runRepo.finish(runId, RunStatus.CANCELLED, FetchPipeline.statsJson(stats));
                return;
            }

            runRepo.finish(runId, RunStatus.COMPLETED, FetchPipeline.statsJson(stats));
            exportDate.forEach(syncState::setLastChangeSyncDate);
            log.info("[run {}] Full load completed; stats={}", runId, FetchPipeline.statsJson(stats));
        } catch (Exception e) {
            log.error("[run {}] Full load failed: {}", runId, e.getMessage(), e);
            runRepo.finish(runId, RunStatus.FAILED, "{}");
        }
    }

    private void runRetryStuck(long runId) {
        log.info("[run {}] Retry of stuck queue entries started", runId);
        try {
            int reset = queueRepo.resetFailedAttempts();
            if (reset > 0) log.info("[run {}] Reset {} FAILED rows for a fresh retry", runId, reset);

            FetchPipeline.StatsSnapshot stats = pipeline.execute(runId, FetchPipeline.Options.incremental());
            if (runRegistry.isCancelled(runId)) {
                runRepo.finish(runId, RunStatus.CANCELLED, FetchPipeline.statsJson(stats));
                return;
            }
            runRepo.finish(runId, RunStatus.COMPLETED, FetchPipeline.statsJson(stats));
            log.info("[run {}] Retry completed; stats={}", runId, FetchPipeline.statsJson(stats));

            edgeBuildService.runIncrementalEdgesSync(runId);
        } catch (Exception e) {
            log.error("[run {}] Retry failed: {}", runId, e.getMessage(), e);
            runRepo.finish(runId, RunStatus.FAILED, "{}");
        }
    }

    private Option<LocalDate> stageA(long runId) throws Exception {
        var genres = tmdb.getGenres();
        if (genres.genres() != null) {
            var queries = genres.genres().stream()
                    .map(g -> ctx.insertInto(GENRE, GENRE.GENRE_ID, GENRE.NAME)
                            .values(g.id(), g.name())
                            .onConflict(GENRE.GENRE_ID)
                            .doUpdate()
                            .set(GENRE.NAME, g.name()))
                    .toList();
            ctx.batch(queries).execute();
        }
        LocalDate exportDate = downloadAndSeedExport(runId);
        log.info("[run {}] Stage A complete; export date={}", runId, exportDate);
        return Option.of(exportDate);
    }

    private LocalDate downloadAndSeedExport(long runId) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int daysBack = 0; daysBack <= EXPORT_FALLBACK_DAYS; daysBack++) {
            LocalDate date = today.minusDays(daysBack);
            try {
                long seeded = seedFromExport(runId, date);
                if (seeded == 0) continue;
                log.info("[run {}] Seeded {} IDs from export {}", runId, seeded, date);
                return date;
            } catch (ExportNotFoundException e) {
                log.info("[run {}] Export {} not available, trying earlier date", runId, date);
            }
        }
        throw new IllegalStateException("Could not find a valid movie ID export after "
                + (EXPORT_FALLBACK_DAYS + 1) + " attempts");
    }

    private long seedFromExport(long runId, LocalDate date) throws Exception {
        long count = 0;
        List<Long> batch = new ArrayList<>(SEED_BATCH_SIZE);
        try (Stream<IdLine> lines = tmdb.downloadIdExport(date)) {
            for (var iter = lines.iterator(); iter.hasNext() && !runRegistry.isCancelled(runId); ) {
                IdLine line = iter.next();
                if (skipAdult && line.adult()) continue;
                if (skipVideo && line.video()) continue;
                batch.add(line.id());
                count++;
                if (limitIds > 0 && count >= limitIds) break;
                if (batch.size() >= SEED_BATCH_SIZE) {
                    queueRepo.seedBatch(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) queueRepo.seedBatch(batch);
        return count;
    }
}
