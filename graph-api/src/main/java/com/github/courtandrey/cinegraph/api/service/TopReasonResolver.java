package com.github.courtandrey.cinegraph.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.courtandrey.cinegraph.api.domain.ComponentType;
import org.springframework.stereotype.Service;


@Service
public class TopReasonResolver {

    public String resolve(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return "Related films";

        JsonNode best = null;
        double maxScore = Double.NEGATIVE_INFINITY;
        for (JsonNode c : arr) {
            double score = c.path("score").asDouble(0);
            if (score > maxScore) { maxScore = score; best = c; }
        }
        return best != null ? humanize(best) : "Related films";
    }

    private String humanize(JsonNode c) {
        return ComponentType.of(c.path("type").asText("")).map(type -> switch (type) {
            case SHARED_PERSON -> humanizePerson(c);
            case SHARED_GENRES -> humanizeGenres(c);
            case SHARED_KEYWORDS -> {
                int count = c.path("count").asInt(0);
                yield count + " shared keyword" + (count != 1 ? "s" : "");
            }
            case RELEASE_PROXIMITY -> {
                int delta = c.path("deltaYears").asInt(0);
                yield delta == 0 ? "Released same year"
                        : "Released " + delta + " year" + (delta != 1 ? "s" : "") + " apart";
            }
        }).orElse("Related films");
    }

    private String humanizePerson(JsonNode c) {
        String roleA   = c.path("roleA").asText("");
        String roleB   = c.path("roleB").asText("");
        boolean same   = c.path("sameRole").asBoolean(false);
        String name    = c.path("name").asText(null);

        if (same) {
            return switch (roleA) {
                case "DIRECTOR"            -> "Same director";
                case "WRITER"              -> "Same writer";
                case "DOP"                 -> "Same director of photography";
                case "EDITOR"              -> "Same editor";
                case "COMPOSER"            -> "Same composer";
                case "PRODUCER"            -> "Same producer";
                case "PRODUCTION_DESIGNER" -> "Same production designer";
                case "COSTUME_DESIGNER"    -> "Same costume designer";
                case "EXEC_PRODUCER"       -> "Same executive producer";
                case "CAST_LEAD"           -> "Two leads in common";
                case "CAST_SUPPORT"        -> "Supporting actor in common";
                case "CAST_MINOR"          -> "Actor in common";
                default                    -> name != null ? name : "Shared crew";
            };
        }
        if (name != null) {
            return name + " (" + roleLabel(roleA) + " → " + roleLabel(roleB) + ")";
        }
        return roleLabel(roleA) + " → " + roleLabel(roleB);
    }

    private String humanizeGenres(JsonNode c) {
        JsonNode names = c.path("names");
        if (names.isArray() && !names.isEmpty()) {
            String first = names.get(0).asText();
            int extra = names.size() - 1;
            return extra > 0 ? "Shared genres: " + first + " +" + extra
                             : "Shared genre: " + first;
        }
        return "Shared genres";
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "DIRECTOR"   -> "director";
            case "WRITER"     -> "writer";
            case "DOP"        -> "DOP";
            case "EDITOR"     -> "editor";
            case "COMPOSER"   -> "composer";
            case "CAST_LEAD"  -> "lead";
            case "CAST_SUPPORT" -> "supporting";
            default           -> role.toLowerCase().replace("_", " ");
        };
    }
}
