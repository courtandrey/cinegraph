package com.github.courtandrey.cinegraph.api.repo;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class Pg {

    private Pg() {}

    public static String tsQuery(String q) {
        return Arrays.stream(q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(Predicate.not(String::isBlank))
                .map(w -> w + ":*")
                .collect(Collectors.joining(" & "));
    }

    public static Condition ftsMatch(Field<String> title, Field<String> originalTitle, String tsQuery) {
        return DSL.condition(
                "to_tsvector('simple', {0} || ' ' || coalesce({1}, '')) @@ to_tsquery('simple', {2})",
                title, originalTitle, DSL.val(tsQuery));
    }

    public static Condition wordSimilarTo(String query, Field<String> column) {
        return DSL.condition("{0} <% {1}", DSL.val(query), column);
    }

    /** Only effective inside a transaction; scopes the {@code <%} operator's threshold to it. */
    public static void setWordSimilarityThreshold(DSLContext ctx, double threshold) {
        ctx.execute("SET LOCAL pg_trgm.word_similarity_threshold = " + threshold);
    }
}
