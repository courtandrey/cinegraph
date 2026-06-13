package com.github.courtandrey.cinegraph.exporter.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.courtandrey.cinegraph.exporter.domain.RunKind;
import com.github.courtandrey.cinegraph.exporter.domain.RunStatus;
import io.vavr.control.Try;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.LOAD_RUN;
import static org.jooq.impl.DSL.currentOffsetDateTime;

@Repository
public class LoadRunRepository {

    private final DSLContext ctx;
    private final ObjectMapper mapper;

    public LoadRunRepository(DSLContext ctx, ObjectMapper mapper) {
        this.ctx = ctx;
        this.mapper = mapper;
    }

    public long create(RunKind kind) {
        return ctx.insertInto(LOAD_RUN)
                .set(LOAD_RUN.KIND, kind.name())
                .set(LOAD_RUN.STATUS, RunStatus.RUNNING.name())
                .returning(LOAD_RUN.ID)
                .fetchSingle(LOAD_RUN.ID);
    }

    public Optional<Map<String, Object>> findById(long id) {
        return ctx.select(LOAD_RUN.ID, LOAD_RUN.KIND, LOAD_RUN.STATUS,
                        LOAD_RUN.STARTED_AT, LOAD_RUN.FINISHED_AT, LOAD_RUN.STATS)
                .from(LOAD_RUN)
                .where(LOAD_RUN.ID.eq(id))
                .fetchOptional()
                .map(r -> {
                    Map<String, Object> run = new LinkedHashMap<>();
                    run.put("id", r.get(LOAD_RUN.ID));
                    run.put("kind", r.get(LOAD_RUN.KIND));
                    run.put("status", r.get(LOAD_RUN.STATUS));
                    run.put("started_at", r.get(LOAD_RUN.STARTED_AT));
                    run.put("finished_at", r.get(LOAD_RUN.FINISHED_AT));
                    run.put("stats", parseStats(r.get(LOAD_RUN.STATS)));
                    return run;
                });
    }

    private JsonNode parseStats(JSONB stats) {
        return Try.of(() -> mapper.readTree(stats.data()))
                .getOrElse(mapper::createObjectNode);
    }

    public boolean hasRunning(RunKind... kinds) {
        return ctx.fetchExists(LOAD_RUN,
                LOAD_RUN.KIND.in(Arrays.stream(kinds).map(Enum::name).toList())
                        .and(LOAD_RUN.STATUS.eq(RunStatus.RUNNING.name())));
    }

    public void updateStats(long id, String statsJson) {
        ctx.update(LOAD_RUN)
                .set(LOAD_RUN.STATS, JSONB.valueOf(statsJson))
                .where(LOAD_RUN.ID.eq(id))
                .execute();
    }

    public void finish(long id, RunStatus status, String statsJson) {
        ctx.update(LOAD_RUN)
                .set(LOAD_RUN.STATUS, status.name())
                .set(LOAD_RUN.FINISHED_AT, currentOffsetDateTime())
                .set(LOAD_RUN.STATS, JSONB.valueOf(statsJson))
                .where(LOAD_RUN.ID.eq(id))
                .execute();
    }
}
