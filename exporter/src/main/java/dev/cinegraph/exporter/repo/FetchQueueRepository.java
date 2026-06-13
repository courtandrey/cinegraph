package dev.cinegraph.exporter.repo;

import dev.cinegraph.exporter.domain.QueueState;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.cinegraph.exporter.jooq.Tables.FETCH_QUEUE;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

@Repository
public class FetchQueueRepository {

    private static final int SEED_BATCH = 500;
    private static final short MAX_ATTEMPTS = 5;

    private final DSLContext ctx;

    public FetchQueueRepository(DSLContext ctx) {
        this.ctx = ctx;
    }

    public void seedBatch(List<Long> movieIds) {
        var insert = ctx.insertInto(FETCH_QUEUE, FETCH_QUEUE.MOVIE_ID)
                .values((Long) null)
                .onConflict(FETCH_QUEUE.MOVIE_ID)
                .doNothing();
        var batch = ctx.batch(insert);
        movieIds.forEach(batch::bind);
        batch.execute();
    }

    public int resetInFlight() {
        return transition(QueueState.IN_FLIGHT, QueueState.PENDING);
    }

    public int resetFailedAttempts() {
        return ctx.update(FETCH_QUEUE)
                .set(FETCH_QUEUE.STATE, QueueState.PENDING.name())
                .set(FETCH_QUEUE.ATTEMPTS, (short) 0)
                .set(FETCH_QUEUE.UPDATED_AT, currentOffsetDateTime())
                .where(FETCH_QUEUE.STATE.eq(QueueState.FAILED.name()))
                .execute();
    }

    public List<Long> claimPending(int limit) {
        return ctx.update(FETCH_QUEUE)
                .set(FETCH_QUEUE.STATE, QueueState.IN_FLIGHT.name())
                .set(FETCH_QUEUE.UPDATED_AT, currentOffsetDateTime())
                .where(FETCH_QUEUE.MOVIE_ID.in(
                        select(FETCH_QUEUE.MOVIE_ID)
                                .from(FETCH_QUEUE)
                                .where(FETCH_QUEUE.STATE.eq(QueueState.PENDING.name())
                                        .or(FETCH_QUEUE.STATE.eq(QueueState.FAILED.name())
                                                .and(FETCH_QUEUE.ATTEMPTS.lt(MAX_ATTEMPTS))))
                                .orderBy(FETCH_QUEUE.MOVIE_ID)
                                .limit(limit)
                                .forUpdate().skipLocked()))
                .returning(FETCH_QUEUE.MOVIE_ID)
                .fetch(FETCH_QUEUE.MOVIE_ID);
    }

    public void markDone(Collection<Long> movieIds) {
        ctx.update(FETCH_QUEUE)
                .set(FETCH_QUEUE.STATE, QueueState.DONE.name())
                .set(FETCH_QUEUE.UPDATED_AT, currentOffsetDateTime())
                .where(FETCH_QUEUE.MOVIE_ID.in(movieIds))
                .execute();
    }

    public void markGone(long movieId) {
        ctx.update(FETCH_QUEUE)
                .set(FETCH_QUEUE.STATE, QueueState.GONE.name())
                .set(FETCH_QUEUE.UPDATED_AT, currentOffsetDateTime())
                .where(FETCH_QUEUE.MOVIE_ID.eq(movieId))
                .execute();
    }

    public void markFailed(long movieId, String error) {
        ctx.update(FETCH_QUEUE)
                .set(FETCH_QUEUE.STATE, QueueState.FAILED.name())
                .set(FETCH_QUEUE.ATTEMPTS, FETCH_QUEUE.ATTEMPTS.plus(1))
                .set(FETCH_QUEUE.LAST_ERROR, error)
                .set(FETCH_QUEUE.UPDATED_AT, currentOffsetDateTime())
                .where(FETCH_QUEUE.MOVIE_ID.eq(movieId))
                .execute();
    }

    public void requeueForIncremental(List<Long> movieIds) {
        var upsert = ctx.insertInto(FETCH_QUEUE, FETCH_QUEUE.MOVIE_ID, FETCH_QUEUE.STATE, FETCH_QUEUE.ATTEMPTS)
                .values(val((Long) null), inline(QueueState.PENDING.name()), inline((short) 0))
                .onConflict(FETCH_QUEUE.MOVIE_ID)
                .doUpdate()
                .set(FETCH_QUEUE.STATE, inline(QueueState.PENDING.name()))
                .set(FETCH_QUEUE.ATTEMPTS, inline((short) 0))
                .set(FETCH_QUEUE.UPDATED_AT, currentOffsetDateTime());
        var batch = ctx.batch(upsert);
        movieIds.forEach(batch::bind);
        batch.execute();
    }

    public Map<String, Long> stateCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        ctx.select(FETCH_QUEUE.STATE, count())
                .from(FETCH_QUEUE)
                .groupBy(FETCH_QUEUE.STATE)
                .forEach(r -> result.put(r.value1(), r.value2().longValue()));
        return result;
    }

    private int transition(QueueState from, QueueState to) {
        return ctx.update(FETCH_QUEUE)
                .set(FETCH_QUEUE.STATE, to.name())
                .set(FETCH_QUEUE.UPDATED_AT, currentOffsetDateTime())
                .where(FETCH_QUEUE.STATE.eq(from.name()))
                .execute();
    }
}
