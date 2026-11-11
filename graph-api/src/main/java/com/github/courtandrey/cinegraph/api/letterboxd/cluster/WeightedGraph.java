package com.github.courtandrey.cinegraph.api.letterboxd.cluster;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

public final class WeightedGraph {

    public record Link(int a, int b, double weight) {}

    private final int[][] neighbours;
    private final double[][] weights;
    private final double[] selfLoops;

    private WeightedGraph(int[][] neighbours, double[][] weights, double[] selfLoops) {
        this.neighbours = neighbours;
        this.weights = weights;
        this.selfLoops = selfLoops;
    }

    public static WeightedGraph of(int size, List<Link> links) {
        List<TreeMap<Integer, Double>> adjacency = emptyAdjacency(size);
        links.stream()
                .filter(l -> l.a() != l.b() && l.weight() > 0)
                .forEach(l -> {
                    adjacency.get(l.a()).merge(l.b(), l.weight(), Double::sum);
                    adjacency.get(l.b()).merge(l.a(), l.weight(), Double::sum);
                });
        return fromAdjacency(adjacency, new double[size]);
    }

    static List<TreeMap<Integer, Double>> emptyAdjacency(int size) {
        return IntStream.range(0, size).mapToObj(i -> new TreeMap<Integer, Double>()).toList();
    }

    static WeightedGraph fromAdjacency(List<? extends Map<Integer, Double>> adjacency, double[] selfLoops) {
        int size = adjacency.size();
        int[][] neighbours = new int[size][];
        double[][] weights = new double[size][];
        for (int i = 0; i < size; i++) {
            Map<Integer, Double> row = adjacency.get(i);
            neighbours[i] = row.keySet().stream().mapToInt(Integer::intValue).toArray();
            weights[i] = row.values().stream().mapToDouble(Double::doubleValue).toArray();
        }
        return new WeightedGraph(neighbours, weights, selfLoops.clone());
    }

    public int size() {
        return neighbours.length;
    }

    public int[] neighbours(int node) {
        return neighbours[node];
    }

    public double[] weights(int node) {
        return weights[node];
    }

    public double selfLoop(int node) {
        return selfLoops[node];
    }

    public double degree(int node) {
        double sum = 2 * selfLoops[node];
        for (double w : weights[node]) sum += w;
        return sum;
    }

    public double totalDegree() {
        return IntStream.range(0, size()).mapToDouble(this::degree).sum();
    }

    public int[] components() {
        int[] label = new int[size()];
        Arrays.fill(label, -1);
        int next = 0;
        for (int start = 0; start < size(); start++) {
            if (label[start] >= 0) continue;
            flood(start, next++, label, n -> true);
        }
        return label;
    }

    void flood(int start, int value, int[] label, IntPredicate admits) {
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        label[start] = value;
        stack.push(start);
        while (!stack.isEmpty()) {
            int u = stack.pop();
            for (int v : neighbours[u]) {
                if (label[v] < 0 && admits.test(v)) {
                    label[v] = value;
                    stack.push(v);
                }
            }
        }
    }
}
