package com.github.courtandrey.cinegraph.exporter.ingest;

import com.github.courtandrey.cinegraph.exporter.admin.RunRegistry;
import com.github.courtandrey.cinegraph.exporter.domain.RunKind;
import com.github.courtandrey.cinegraph.exporter.domain.RunStatus;
import com.github.courtandrey.cinegraph.exporter.repo.FetchQueueRepository;
import com.github.courtandrey.cinegraph.exporter.repo.LoadRunRepository;
import com.github.courtandrey.cinegraph.exporter.repo.SyncStateRepository;
import com.github.courtandrey.cinegraph.exporter.tmdb.TmdbClient;
import com.github.courtandrey.cinegraph.exporter.tmdb.dto.ChangesPage;
import io.vavr.control.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class IncrementalLoadService {

    public record DateRange(LocalDate start, LocalDate end) {}

    private static final Logger log = LoggerFactory.getLogger(IncrementalLoadService.class);
    private static final int MAX_CHUNK_DAYS = 14;
    private static final int SEED_BATCH_SIZE = 500;

    private final TmdbClient tmdb;
    private final FetchQueueRepository queueRepo;
    private final LoadRunRepository runRepo;
    private final FetchPipeline pipeline;
    private final RunRegistry runRegistry;
    private final EdgeBuildService edgeBuildService;
    private final SyncStateRepository syncState;

    @Value("${ingest.skip-adult:true}") private boolean skipAdult;
    @Value("${ingest.max-catchup-days:90}") private int maxCatchupDays;

    public IncrementalLoadService(TmdbClient tmdb, FetchQueueRepository queueRepo,
                                  LoadRunRepository runRepo, FetchPipeline pipeline,
                                  RunRegistry runRegistry, EdgeBuildService edgeBuildService,
                                  SyncStateRepository syncState) {
        this.tmdb = tmdb;
        this.queueRepo = queueRepo;
        this.runRepo = runRepo;
        this.pipeline = pipeline;
        this.runRegistry = runRegistry;
        this.edgeBuildService = edgeBuildService;
        this.syncState = syncState;
    }

    @Scheduled(cron = "0 30 9 * * *", zone = "UTC")
    public void scheduledRun() {
        if (runRepo.hasRunning(RunKind.FULL, RunKind.INCREMENTAL)) {
            log.info("Skipping scheduled incremental: another load is already running");
            return;
        }
        io.vavr.control.Try.run(this::triggerIncremental)
                .onFailure(e -> log.error("Scheduled incremental failed to start: {}", e.getMessage(), e));
    }

    public long triggerIncremental() {
        if (runRepo.hasRunning(RunKind.FULL)) {
            throw new IllegalStateException("A FULL load is running; wait for it to finish");
        }
        if (runRepo.hasRunning(RunKind.INCREMENTAL)) {
            throw new IllegalStateException("An INCREMENTAL load is already running");
        }
        long runId = runRepo.create(RunKind.INCREMENTAL);
        runRegistry.launch(runId, () -> runIncremental(runId));
        return runId;
    }

    static List<DateRange> dateChunks(LocalDate from, LocalDate to) {
        List<DateRange> chunks = new ArrayList<>();
        LocalDate start = from;
        while (!start.isAfter(to)) {
            LocalDate end = start.plusDays(MAX_CHUNK_DAYS);
            chunks.add(new DateRange(start, end.isAfter(to) ? to : end));
            start = end.plusDays(1);
        }
        return chunks;
    }

    private void runIncremental(long runId) {
        log.info("[run {}] Incremental load started", runId);
        try {
            Option<LocalDate> lastSync = syncState.lastChangeSyncDate();
            if (lastSync.isEmpty()) {
                log.error("[run {}] No last_change_sync_date in sync_state. Run a full load first.", runId);
                runRepo.finish(runId, RunStatus.FAILED, "{\"error\":\"no_sync_state\"}");
                return;
            }

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            long gapDays = ChronoUnit.DAYS.between(lastSync.get(), today);
            if (gapDays > maxCatchupDays) {
                log.error("[run {}] Catchup gap of {} days exceeds max-catchup-days={}. Run a full load instead.",
                        runId, gapDays, maxCatchupDays);
                runRepo.finish(runId, RunStatus.FAILED,
                        "{\"error\":\"catchup_gap_exceeded\",\"gap_days\":" + gapDays + "}");
                return;
            }

            Set<Long> changedIds = collectChangedIds(runId, lastSync.get(), today);
            log.info("[run {}] Collected {} distinct changed IDs over {} days",
                    runId, changedIds.size(), gapDays);
            if (runRegistry.isCancelled(runId)) {
                runRepo.finish(runId, RunStatus.CANCELLED, "{}");
                return;
            }

            if (changedIds.isEmpty()) {
                syncState.setLastChangeSyncDate(today);
                runRepo.finish(runId, RunStatus.COMPLETED,
                        "{\"fetched\":0,\"upserted\":0,\"changed_ids\":0}");
                return;
            }

            requeueIds(changedIds);
            FetchPipeline.StatsSnapshot stats = pipeline.execute(runId, FetchPipeline.Options.incremental());
            if (runRegistry.isCancelled(runId)) {
                runRepo.finish(runId, RunStatus.CANCELLED, FetchPipeline.statsJson(stats));
                return;
            }

            syncState.setLastChangeSyncDate(today);
            runRepo.finish(runId, RunStatus.COMPLETED, FetchPipeline.statsJson(stats));
            log.info("[run {}] Incremental load completed; stats={}", runId, FetchPipeline.statsJson(stats));

            edgeBuildService.runIncrementalEdgesSync(runId);
        } catch (Exception e) {
            log.error("[run {}] Incremental load failed: {}", runId, e.getMessage(), e);
            runRepo.finish(runId, RunStatus.FAILED, "{}");
        }
    }

    private Set<Long> collectChangedIds(long runId, LocalDate from, LocalDate to) throws Exception {
        Set<Long> ids = new LinkedHashSet<>();
        for (DateRange chunk : dateChunks(from, to)) {
            if (runRegistry.isCancelled(runId)) break;
            ids.addAll(collectChunk(chunk));
        }
        return ids;
    }

    private Set<Long> collectChunk(DateRange range) throws Exception {
        Set<Long> ids = new LinkedHashSet<>();
        int page = 1;
        while (true) {
            ChangesPage response = tmdb.getChanges(range.start(), range.end(), page);
            if (response.results() != null) {
                for (ChangesPage.ChangeEntry entry : response.results()) {
                    if (skipAdult && entry.adult()) continue;
                    ids.add(entry.id());
                }
            }
            if (page >= response.totalPages()) break;
            page++;
        }
        return ids;
    }

    private void requeueIds(Set<Long> movieIds) {
        List<Long> batch = new ArrayList<>(SEED_BATCH_SIZE);
        for (long id : movieIds) {
            batch.add(id);
            if (batch.size() >= SEED_BATCH_SIZE) {
                queueRepo.requeueForIncremental(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) queueRepo.requeueForIncremental(batch);
    }
}
