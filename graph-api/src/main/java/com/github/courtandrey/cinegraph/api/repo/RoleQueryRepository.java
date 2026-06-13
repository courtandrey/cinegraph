package com.github.courtandrey.cinegraph.api.repo;

import com.github.courtandrey.cinegraph.api.dto.RoleWeight;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.courtandrey.cinegraph.api.jooq.Tables.ROLE;

@Repository
public class RoleQueryRepository {

    private final DSLContext ctx;

    public RoleQueryRepository(DSLContext ctx) {
        this.ctx = ctx;
    }

    public List<RoleWeight> findAll() {
        return ctx.select(ROLE.CODE, ROLE.BASE_WEIGHT)
                .from(ROLE)
                .orderBy(ROLE.BASE_WEIGHT.desc(), ROLE.CODE)
                .fetch(r -> new RoleWeight(r.value1(), r.value2()));
    }

    public Map<String, Double> defaultWeights() {
        return ctx.select(ROLE.CODE, ROLE.BASE_WEIGHT)
                .from(ROLE)
                .fetch()
                .stream()
                .collect(Collectors.toMap(r -> r.value1(), r -> (double) r.value2()));
    }
}
