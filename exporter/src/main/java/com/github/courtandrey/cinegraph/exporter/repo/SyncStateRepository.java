package com.github.courtandrey.cinegraph.exporter.repo;

import io.vavr.control.Option;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.SYNC_STATE;

@Repository
public class SyncStateRepository {

    private static final String LAST_CHANGE_SYNC_DATE = "last_change_sync_date";

    private final DSLContext ctx;

    public SyncStateRepository(DSLContext ctx) {
        this.ctx = ctx;
    }

    public Option<LocalDate> lastChangeSyncDate() {
        return Option.ofOptional(
                        ctx.select(SYNC_STATE.VALUE).from(SYNC_STATE)
                                .where(SYNC_STATE.KEY.eq(LAST_CHANGE_SYNC_DATE))
                                .fetchOptional(SYNC_STATE.VALUE))
                .map(LocalDate::parse);
    }

    public void setLastChangeSyncDate(LocalDate date) {
        ctx.insertInto(SYNC_STATE, SYNC_STATE.KEY, SYNC_STATE.VALUE)
                .values(LAST_CHANGE_SYNC_DATE, date.toString())
                .onConflict(SYNC_STATE.KEY)
                .doUpdate()
                .set(SYNC_STATE.VALUE, date.toString())
                .execute();
    }
}
