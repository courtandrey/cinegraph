package com.github.courtandrey.cinegraph.exporter.repo;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;

import static com.github.courtandrey.cinegraph.exporter.jooq.Tables.ROLE;

@Repository
public class RoleRepository {

    private final DSLContext ctx;

    public RoleRepository(DSLContext ctx) {
        this.ctx = ctx;
    }

    public void seed(Map<String, Double> weightsByCode) {
        var queries = weightsByCode.entrySet().stream()
                .map(e -> ctx.insertInto(ROLE, ROLE.CODE, ROLE.BASE_WEIGHT)
                        .values(e.getKey(), e.getValue().floatValue())
                        .onConflict(ROLE.CODE)
                        .doUpdate()
                        .set(ROLE.BASE_WEIGHT, e.getValue().floatValue()))
                .toList();
        ctx.batch(queries).execute();
    }

    public void ensureExist(Collection<String> codes, double defaultWeight) {
        if (codes.isEmpty()) return;
        var queries = codes.stream()
                .map(code -> ctx.insertInto(ROLE, ROLE.CODE, ROLE.BASE_WEIGHT)
                        .values(code, (float) defaultWeight)
                        .onConflict(ROLE.CODE)
                        .doNothing())
                .toList();
        ctx.batch(queries).execute();
    }
}
