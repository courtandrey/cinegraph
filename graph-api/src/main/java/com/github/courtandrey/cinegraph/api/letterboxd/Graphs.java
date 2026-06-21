package com.github.courtandrey.cinegraph.api.letterboxd;

import com.github.courtandrey.cinegraph.api.dto.GraphEdge;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Splits a flat edge list into connected components via union-find. Nodes that take
 * part in no edge never appear, so orphaned films drop out for free; two groups with
 * no edge between them surface as separate components.
 */
public final class Graphs {

    private Graphs() {}

    public record Component(List<Long> nodeIds, List<GraphEdge> edges) {}

    public static List<Component> components(List<GraphEdge> edges) {
        Map<Long, Long> parent = new HashMap<>();
        edges.forEach(e -> union(parent, e.source(), e.target()));

        Map<Long, List<GraphEdge>> edgesByRoot = new LinkedHashMap<>();
        Map<Long, LinkedHashSet<Long>> nodesByRoot = new LinkedHashMap<>();
        for (GraphEdge e : edges) {
            long root = find(parent, e.source());
            edgesByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(e);
            LinkedHashSet<Long> nodes = nodesByRoot.computeIfAbsent(root, k -> new LinkedHashSet<>());
            nodes.add(e.source());
            nodes.add(e.target());
        }

        return edgesByRoot.entrySet().stream()
                .map(en -> new Component(
                        new ArrayList<>(nodesByRoot.get(en.getKey())),
                        en.getValue()))
                .toList();
    }

    public static Component capNodes(Component c, int maxNodes) {
        if (c.nodeIds().size() <= maxNodes) return c;
        Map<Long, Double> sums = edgeSums(c.edges());
        Set<Long> kept = sums.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(maxNodes)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        List<GraphEdge> edges = c.edges().stream()
                .filter(e -> kept.contains(e.source()) && kept.contains(e.target()))
                .toList();
        return new Component(nodeIdsOf(edges), edges);
    }

    public static Component capEdgesPerNode(Component c, int minPerNode, float minScoreForNonEssentail) {
        Map<Long, List<GraphEdge>> byNode = new HashMap<>();
        for (GraphEdge e : c.edges()) {
            byNode.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e);
            byNode.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e);
        }
        LinkedHashSet<GraphEdge> kept = new LinkedHashSet<>();
        for (List<GraphEdge> nodeEdges : byNode.values()) {
            List<GraphEdge> graphEdgeStream = nodeEdges.stream()
                    .sorted(Comparator.comparingDouble(GraphEdge::score).reversed())
                    .toList();
            graphEdgeStream
                    .stream()
                    .limit(minPerNode)
                    .forEach(kept::add);
            graphEdgeStream
                    .stream()
                    .skip(minPerNode)
                    .filter(node -> node.score() > minScoreForNonEssentail)
                    .forEach(kept::add);
        }
        if (kept.size() == c.edges().size()) return c;
        List<GraphEdge> edges = List.copyOf(kept);
        return new Component(nodeIdsOf(edges), edges);
    }

    public static Map<Long, Double> edgeSums(List<GraphEdge> edges) {
        Map<Long, Double> sums = new HashMap<>();
        for (GraphEdge e : edges) {
            sums.merge(e.source(), (double) e.score(), Double::sum);
            sums.merge(e.target(), (double) e.score(), Double::sum);
        }
        return sums;
    }

    private static List<Long> nodeIdsOf(List<GraphEdge> edges) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (GraphEdge e : edges) {
            ids.add(e.source());
            ids.add(e.target());
        }
        return new ArrayList<>(ids);
    }

    private static long find(Map<Long, Long> parent, long node) {
        long root = node;
        while (!parent.getOrDefault(root, root).equals(root)) {
            root = parent.get(root);
        }
        long cur = node;
        while (cur != root) {
            cur = parent.put(cur, root);
        }
        return root;
    }

    private static void union(Map<Long, Long> parent, long a, long b) {
        long ra = find(parent, a);
        long rb = find(parent, b);
        if (ra != rb) parent.put(ra, rb);
    }
}
