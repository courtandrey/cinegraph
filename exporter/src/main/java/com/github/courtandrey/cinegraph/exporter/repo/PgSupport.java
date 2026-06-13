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
}
