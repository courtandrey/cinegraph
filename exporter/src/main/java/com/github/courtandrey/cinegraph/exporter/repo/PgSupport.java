package com.github.courtandrey.cinegraph.exporter.repo;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

public final class PgSupport {

    private PgSupport() {}

    public static Field<JSONB> jsonbConcat(Field<JSONB> first, Field<JSONB>... rest) {
        Field<JSONB> acc = first;
        for (Field<JSONB> f : rest) {
            acc = DSL.field("({0} || {1})", JSONB.class, acc, f);
        }
        return acc;
    }

    public static Field<JSONB> jsonbConcatAgg(Field<JSONB> arrays) {
        return DSL.aggregate("jsonb_concat_agg", SQLDataType.JSONB, arrays);
    }

    public static Table<?> jsonbArrayElements(Field<JSONB> array) {
        return DSL.table("jsonb_array_elements({0})", array).asTable("e", "e");
    }

    public static void analyze(DSLContext ctx, Table<?> table) {
        ctx.execute("ANALYZE " + table.getName());
    }

    public static int propagateComponents(DSLContext ctx) {
        return ctx.execute("""
                WITH nbr AS (
                    SELECT e.movie_a AS node, m2.component_id AS lbl
                      FROM edge e JOIN movie m2 ON m2.movie_id = e.movie_b
                    UNION ALL
                    SELECT e.movie_b AS node, m1.component_id AS lbl
                      FROM edge e JOIN movie m1 ON m1.movie_id = e.movie_a
                ),
                best AS (SELECT node, MIN(lbl) AS lbl FROM nbr GROUP BY node)
                UPDATE movie m SET component_id = best.lbl
                  FROM best
                 WHERE m.movie_id = best.node AND best.lbl < m.component_id
                """);
    }

}
