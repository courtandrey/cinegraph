package com.github.courtandrey.cinegraph.api.letterboxd.cluster;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Deterministic Louvain community detection. After every local-moving phase each community is
 * split into its connected parts, so every returned community is internally connected. Nodes
 * are visited in index order and ties keep the current community, so equal input always yields
 * equal output. Labels are dense, numbered in order of each community's lowest node index.
 */
public final class Communities {

    private static final int MAX_PASSES = 64;
    private static final double MIN_GAIN = 1e-12;

    private Communities() {}

    public static int[] detect(WeightedGraph graph, double resolution) {
        int[] partition = IntStream.range(0, graph.size()).toArray();
        WeightedGraph level = graph;
        while (true) {
            int[] local = connectedParts(level, moveNodes(level, resolution));
            int count = Arrays.stream(local).max().orElse(-1) + 1;
            if (count == level.size()) return relabelByFirstMember(partition);
            int[] previous = partition;
            partition = Arrays.stream(previous).map(c -> local[c]).toArray();
            level = aggregate(level, local, count);
        }
    }

    public static double modularity(WeightedGraph graph, int[] partition, double resolution) {
        double m2 = graph.totalDegree();
        if (m2 == 0) return 0;
        Map<Integer, Double> inside = new TreeMap<>();
        Map<Integer, Double> total = new TreeMap<>();
        for (int i = 0; i < graph.size(); i++) {
            int c = partition[i];
            total.merge(c, graph.degree(i), Double::sum);
            inside.merge(c, 2 * graph.selfLoop(i), Double::sum);
            int[] nbr = graph.neighbours(i);
            double[] w = graph.weights(i);
            for (int k = 0; k < nbr.length; k++) {
                if (partition[nbr[k]] == c) inside.merge(c, w[k], Double::sum);
            }
        }
        return total.keySet().stream()
                .mapToDouble(c -> inside.getOrDefault(c, 0.0) / m2
                        - resolution * Math.pow(total.get(c) / m2, 2))
                .sum();
    }

    private static int[] moveNodes(WeightedGraph g, double resolution) {
        int n = g.size();
        int[] community = IntStream.range(0, n).toArray();
        double[] degree = IntStream.range(0, n).mapToDouble(g::degree).toArray();
        double[] total = degree.clone();
        double m2 = Arrays.stream(degree).sum();
        if (m2 == 0) return community;

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                int own = community[i];
                Map<Integer, Double> linkWeight = weightsToCommunities(g, community, i);
                total[own] -= degree[i];
                int best = own;
                double bestGain = gain(linkWeight.getOrDefault(own, 0.0), total[own], degree[i], m2, resolution);
                for (Map.Entry<Integer, Double> e : linkWeight.entrySet()) {
                    double candidate = gain(e.getValue(), total[e.getKey()], degree[i], m2, resolution);
                    if (candidate > bestGain + MIN_GAIN) {
                        best = e.getKey();
                        bestGain = candidate;
                    }
                }
                total[best] += degree[i];
                if (best != own) {
                    community[i] = best;
                    moved = true;
                }
            }
            if (!moved) break;
        }
        return community;
    }

    private static double gain(double linkWeight, double communityTotal, double degree, double m2, double resolution) {
        return linkWeight - resolution * communityTotal * degree / m2;
    }

    private static Map<Integer, Double> weightsToCommunities(WeightedGraph g, int[] community, int node) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        int[] nbr = g.neighbours(node);
        double[] w = g.weights(node);
        for (int k = 0; k < nbr.length; k++) out.merge(community[nbr[k]], w[k], Double::sum);
        return out;
    }

    private static int[] connectedParts(WeightedGraph g, int[] community) {
        int[] label = new int[g.size()];
        Arrays.fill(label, -1);
        int next = 0;
        for (int start = 0; start < g.size(); start++) {
            if (label[start] >= 0) continue;
            int own = community[start];
            g.flood(start, next++, label, v -> community[v] == own);
        }
        return label;
    }

    private static WeightedGraph aggregate(WeightedGraph g, int[] community, int count) {
        List<TreeMap<Integer, Double>> adjacency = WeightedGraph.emptyAdjacency(count);
        double[] selfLoops = new double[count];
        for (int i = 0; i < g.size(); i++) {
            int c = community[i];
            selfLoops[c] += g.selfLoop(i);
            int[] nbr = g.neighbours(i);
            double[] w = g.weights(i);
            for (int k = 0; k < nbr.length; k++) {
                int d = community[nbr[k]];
                if (c == d) selfLoops[c] += w[k] / 2;
                else adjacency.get(c).merge(d, w[k], Double::sum);
            }
        }
        return WeightedGraph.fromAdjacency(adjacency, selfLoops);
    }

    private static int[] relabelByFirstMember(int[] partition) {
        Map<Integer, Integer> dense = new LinkedHashMap<>();
        return Arrays.stream(partition)
                .map(c -> dense.computeIfAbsent(c, k -> dense.size()))
                .toArray();
    }
}
