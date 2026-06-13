package com.github.courtandrey.cinegraph.exporter.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.courtandrey.cinegraph.exporter.admin.RunRegistry;
import com.github.courtandrey.cinegraph.exporter.ingest.Projections.Fetched;
import com.github.courtandrey.cinegraph.exporter.repo.FetchQueueRepository;
import com.github.courtandrey.cinegraph.exporter.repo.LoadRunRepository;
import com.github.courtandrey.cinegraph.exporter.tmdb.TmdbClient;
import com.github.courtandrey.cinegraph.exporter.tmdb.TmdbClient.TmdbRateLimitException;
import com.github.courtandrey.cinegraph.exporter.tmdb.TmdbProperties;
import io.vavr.control.Try;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.MOVIE;
import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.MOVIE_RAW;

/**
 * Producer/consumer/writer fetch pipeline shared by full, incremental and retry
 * loads. Claims queue batches, fetches via virtual threads bounded by a semaphore,
 * and writes through a single writer thread draining a bounded queue.
 */
@Component
public class FetchPipeline {

    public record Options(boolean deleteOnGone, boolean recordDirty) {
        public static Options fullLoad() {
            return new Options(false, false);
        }

        public static Options incremental() {
            return new Options(true, true);
        }
    }

    public record StatsSnapshot(long fetched, long upserted, long failed, long gone, long http429s) {}

    private static final Logger log = LoggerFactory.getLogger(FetchPipeline.class);
    private static final int RESULT_QUEUE_CAPACITY = 5000;
    private static final int CLAIM_BATCH = 1000;
    private static final int WRITE_BATCH = 200;

    private final TmdbClient tmdb;
    private final FetchQueueRepository queueRepo;
    private final LoadRunRepository runRepo;
    private final ProjectionWriter writer;
    private final RunRegistry runRegistry;
    private final ObjectMapper mapper;
    private final Roles roles;
    private final DSLContext ctx;
    private final int maxConcurrency;

    public FetchPipeline(TmdbClient tmdb, FetchQueueRepository queueRepo,
                         LoadRunRepository runRepo, ProjectionWriter writer,
                         RunRegistry runRegistry, ObjectMapper mapper, Roles roles,
                         DSLContext ctx, TmdbProperties props) {
        this.tmdb = tmdb;
        this.queueRepo = queueRepo;
        this.runRepo = runRepo;
        this.writer = writer;
        this.runRegistry = runRegistry;
        this.mapper = mapper;
        this.roles = roles;
        this.ctx = ctx;
        this.maxConcurrency = props.getMaxConcurrency();
    }

    public StatsSnapshot execute(long runId, Options options) throws InterruptedException {
        queueRepo.resetInFlight();

        Counters counters = new Counters();
        ArrayBlockingQueue<Fetched> resultQueue = new ArrayBlockingQueue<>(RESULT_QUEUE_CAPACITY);
        Semaphore semaphore = new Semaphore(maxConcurrency);

        Thread writerThread = Thread.ofPlatform().name("writer").start(
                () -> writerLoop(resultQueue, runId, counters, options));

        try (ExecutorService fetchExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            producerLoop(runId, fetchExecutor, semaphore, resultQueue, counters, options);
            fetchExecutor.shutdown();
            if (!fetchExecutor.awaitTermination(24, TimeUnit.HOURS)) {
                log.warn("[run {}] Fetch executor did not terminate in time", runId);
            }
        }

        resultQueue.put(Fetched.POISON);
        writerThread.join();

        StatsSnapshot stats = counters.snapshot();
        runRepo.updateStats(runId, statsJson(stats));
        return stats;
    }

    public static String statsJson(StatsSnapshot s) {
        return "{\"fetched\":" + s.fetched()
                + ",\"upserted\":" + s.upserted()
                + ",\"failed\":" + s.failed()
                + ",\"gone\":" + s.gone()
                + ",\"http_429s\":" + s.http429s() + "}";
    }

    private void producerLoop(long runId, ExecutorService fetchExecutor, Semaphore semaphore,
                              ArrayBlockingQueue<Fetched> resultQueue,
                              Counters counters, Options options) {
        while (!runRegistry.isCancelled(runId)) {
            List<Long> ids = queueRepo.claimPending(CLAIM_BATCH);
            if (ids.isEmpty()) {
                if (semaphore.availablePermits() == maxConcurrency) {
                    List<Long> recheck = queueRepo.claimPending(1);
                    if (recheck.isEmpty()) break;
                    ids = recheck;
                } else {
                    if (!sleepQuietly()) break;
                    continue;
                }
            }
            for (long movieId : ids) {
                if (runRegistry.isCancelled(runId)) break;
                fetchExecutor.submit(() -> fetchOne(movieId, semaphore, resultQueue, counters, options));
            }
        }
    }

    private void fetchOne(long movieId, Semaphore semaphore,
                          ArrayBlockingQueue<Fetched> resultQueue,
                          Counters counters, Options options) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailedQuietly(movieId, "Interrupted", counters);
            return;
        }
        try {
            fetchWithRetry(movieId, resultQueue, counters, options);
        } finally {
            semaphore.release();
        }
    }

    private void fetchWithRetry(long movieId, ArrayBlockingQueue<Fetched> resultQueue,
                                Counters counters, Options options) {
        while (true) {
            try {
                var raw = tmdb.getMovieBundle(movieId);
                if (raw.isEmpty()) {
                    queueRepo.markGone(movieId);
                    if (options.deleteOnGone()) deleteMovie(movieId);
                    counters.gone.incrementAndGet();
                    return;
                }
                resultQueue.put(new Fetched(movieId, raw.get()));
                counters.fetched.incrementAndGet();
                return;
            } catch (TmdbRateLimitException e) {
                counters.http429s.incrementAndGet();
                if (!sleepQuietly(e.getRetryAfterSeconds())) return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markFailedQuietly(movieId, "Interrupted", counters);
                return;
            } catch (Exception e) {
                markFailedQuietly(movieId, truncate(e.getMessage()), counters);
                return;
            }
        }
    }

    private void writerLoop(ArrayBlockingQueue<Fetched> resultQueue, long runId,
                            Counters counters, Options options) {
        List<Fetched> batch = new ArrayList<>(WRITE_BATCH);
        long flushCount = 0;

        while (true) {
            try {
                Fetched head = resultQueue.take();
                if (head.isPoison()) {
                    flush(batch, counters, runId, options);
                    return;
                }
                batch.add(head);
                resultQueue.drainTo(batch, WRITE_BATCH - batch.size());

                boolean poisoned = batch.removeIf(Fetched::isPoison);
                if (!batch.isEmpty() && (poisoned || batch.size() >= WRITE_BATCH)) {
                    flush(batch, counters, runId, options);
                }
                if (poisoned) return;

                if (++flushCount % 10 == 0) {
                    runRepo.updateStats(runId, statsJson(counters.snapshot()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void flush(List<Fetched> batch, Counters counters, long runId, Options options) {
        if (batch.isEmpty()) return;
        Long dirtyRunId = options.recordDirty() ? runId : null;
        List<Fetched> toWrite = List.copyOf(batch);
        batch.clear();

        Projections.Batch projected = Projections.project(toWrite, roles, mapper);
        Try.of(() -> writer.write(projected, dirtyRunId))
                .onSuccess(ids -> {
                    counters.upserted.addAndGet(ids.size());
                    if (!ids.isEmpty()) queueRepo.markDone(ids);
                    projected.failures().forEach(f ->
                            markFailedQuietly(f.movieId(), f.reason(), counters));
                })
                .onFailure(e -> {
                    log.error("[run {}] Batch write failed ({}); retrying {} movies individually",
                            runId, e.getMessage(), toWrite.size(), e);
                    toWrite.forEach(fm -> flushSingle(fm, counters, runId, dirtyRunId));
                });
    }

    private void flushSingle(Fetched fm, Counters counters, long runId, Long dirtyRunId) {
        Projections.Batch single = Projections.project(List.of(fm), roles, mapper);
        Try.of(() -> writer.write(single, dirtyRunId))
                .onSuccess(ids -> {
                    if (ids.isEmpty()) {
                        markFailedQuietly(fm.movieId(), failureReason(single), counters);
                    } else {
                        counters.upserted.incrementAndGet();
                        queueRepo.markDone(ids);
                    }
                })
                .onFailure(e -> {
                    log.warn("[run {}] Movie {} failed individual write: {}",
                            runId, fm.movieId(), e.getMessage());
                    markFailedQuietly(fm.movieId(), truncate(e.getMessage()), counters);
                });
    }

    private void markFailedQuietly(long movieId, String error, Counters counters) {
        counters.failed.incrementAndGet();
        Try.run(() -> queueRepo.markFailed(movieId, error))
                .onFailure(e -> log.warn("Could not mark movie {} failed (left IN_FLIGHT): {}",
                        movieId, e.getMessage()));
    }

    private void deleteMovie(long movieId) {
        ctx.deleteFrom(MOVIE).where(MOVIE.MOVIE_ID.eq(movieId)).execute();
        ctx.deleteFrom(MOVIE_RAW).where(MOVIE_RAW.MOVIE_ID.eq(movieId)).execute();
    }

    private static String failureReason(Projections.Batch single) {
        return single.failures().isEmpty()
                ? "Projection failed (unparseable payload)"
                : single.failures().get(0).reason();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    private static boolean sleepQuietly() {
        return sleepQuietly(1);
    }

    private static boolean sleepQuietly(int seconds) {
        return Try.run(() -> Thread.sleep(seconds * 1_000L))
                .onFailure(e -> Thread.currentThread().interrupt())
                .isSuccess();
    }

    private static final class Counters {
        final AtomicLong fetched = new AtomicLong();
        final AtomicLong upserted = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong gone = new AtomicLong();
        final AtomicLong http429s = new AtomicLong();

        StatsSnapshot snapshot() {
            return new StatsSnapshot(fetched.get(), upserted.get(), failed.get(),
                    gone.get(), http429s.get());
        }
    }
}
