package com.github.courtandrey.cinegraph.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

@Service
public class ReasonResolver {

    public String resolveCrewPerson(JsonNode arr) {
        if (arr == null || !arr.isArray()) return "Shared crew";
        JsonNode best = null;
        double maxScore = Double.NEGATIVE_INFINITY;
        for (JsonNode c : arr) {
            if (!"SHARED_PERSON".equals(c.path("type").asText(""))) continue;
            double score = c.path("score").asDouble(0);
            if (score > maxScore) {
                maxScore = score;
                best = c;
            }
        }
        return best != null ? crewLabel(best) : "Shared crew";
    }

    private String crewLabel(JsonNode c) {
        String name = c.path("name").asText(null);
        String roleA = c.path("roleA").asText("");
        String roleB = c.path("roleB").asText("");
        boolean same = c.path("sameRole").asBoolean(false);
        if (name == null || name.isBlank()) {
            return same ? roleLabel(roleA) : roleLabel(roleA) + " → " + roleLabel(roleB);
        }
        return same
                ? name + " · " + roleLabel(roleA)
                : name + " (" + roleLabel(roleA) + " → " + roleLabel(roleB) + ")";
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "DIRECTOR" -> "director";
            case "WRITER" -> "writer";
            case "DOP" -> "DOP";
            case "EDITOR" -> "editor";
            case "COMPOSER" -> "composer";
            case "CAST_LEAD" -> "lead";
            case "CAST_SUPPORT" -> "supporting";
            default -> role.toLowerCase().replace("_", " ");
        };
    }
}
