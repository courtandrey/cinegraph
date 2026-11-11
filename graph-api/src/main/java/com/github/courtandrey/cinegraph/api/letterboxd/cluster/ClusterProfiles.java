package com.github.courtandrey.cinegraph.api.letterboxd.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.courtandrey.cinegraph.api.domain.ComponentType;
import com.github.courtandrey.cinegraph.api.dto.GraphEdge;
import com.github.courtandrey.cinegraph.api.dto.MapCluster.Contributor;
import com.github.courtandrey.cinegraph.api.dto.MapCluster.GenreShare;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository.MovieGenre;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public final class ClusterProfiles {

    private static final String GENERIC_ROLE = "CREW";

    private record Tally(String name, double score, Set<Long> films, Map<String, Integer> roles) {
        Tally add(double s, long a, long b, String roleA, String roleB) {
            films.add(a);
            films.add(b);
            roles.merge(roleA, 1, Integer::sum);
            roles.merge(roleB, 1, Integer::sum);
            return new Tally(name, score + s, films, roles);
        }
    }

    private ClusterProfiles() {}

    public static List<GenreShare> genres(Collection<Long> members, Map<Long, List<MovieGenre>> genresByMovie) {
        Map<Integer, MovieGenre> sample = new HashMap<>();
        Map<Integer, Integer> counts = new TreeMap<>();
        members.stream()
                .flatMap(m -> genresByMovie.getOrDefault(m, List.of()).stream())
                .forEach(g -> {
                    sample.putIfAbsent(g.genreId(), g);
                    counts.merge(g.genreId(), 1, Integer::sum);
                });
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .map(e -> new GenreShare(e.getKey(), sample.get(e.getKey()).name(), e.getValue()))
                .toList();
    }

    public static List<Contributor> contributors(List<GraphEdge> internalEdges, int limit) {
        Map<Long, Tally> byPerson = new HashMap<>();
        for (GraphEdge edge : internalEdges) {
            for (JsonNode c : iterable(edge.components())) {
                if (!ComponentType.SHARED_PERSON.name().equals(c.path("type").asText())) continue;
                long personId = c.path("personId").asLong();
                byPerson.compute(personId, (id, t) -> (t == null
                                ? new Tally(c.path("name").asText(), 0, new HashSet<>(), new HashMap<>())
                                : t)
                        .add(c.path("score").asDouble(), edge.source(), edge.target(),
                                c.path("roleA").asText(GENERIC_ROLE), c.path("roleB").asText(GENERIC_ROLE)));
            }
        }
        return byPerson.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, Tally>>comparingDouble(e -> -e.getValue().score())
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(e -> new Contributor(e.getKey(), e.getValue().name(), dominantRole(e.getValue().roles()),
                        e.getValue().films().size(), e.getValue().score()))
                .toList();
    }

    private static String dominantRole(Map<String, Integer> roles) {
        Map<String, Integer> specific = roles.entrySet().stream()
                .filter(e -> !GENERIC_ROLE.equals(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return specific.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse(GENERIC_ROLE);
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node == null || !node.isArray()
                ? List.of()
                : StreamSupport.stream(node.spliterator(), false).toList();
    }
}
