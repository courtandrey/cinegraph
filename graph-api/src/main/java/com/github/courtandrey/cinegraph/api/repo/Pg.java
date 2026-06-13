package com.github.courtandrey.cinegraph.api.repo;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

public final class Pg {

    private Pg() {}

    public static Condition ilike(Field<String> column, Field<String> pattern) {
        return DSL.condition("{0} ILIKE {1}", column, pattern);
    }

    public static Condition wordSimilar(Field<String> column, String query) {
        return DSL.condition("{0} % {1}", column, DSL.val(query));
    }

    public static Field<Float> similarity(Field<String> column, String query) {
        return DSL.field("similarity({0}, {1})", SQLDataType.REAL, column, DSL.val(query));
    }
}
